package ar.correoargentino.impoexpo.service;

import ar.correoargentino.impoexpo.api.dto.BuscarPosicionesResponse;
import ar.correoargentino.impoexpo.api.dto.BuscarPosicionesResponse.GrupoPosiciones;
import ar.correoargentino.impoexpo.api.dto.BuscarPosicionesResponse.ItemPosicion;
import ar.correoargentino.impoexpo.api.dto.DetallePosicionResponse;
import ar.correoargentino.impoexpo.api.dto.DetallePosicionResponse.NivelJerarquia;
import ar.correoargentino.impoexpo.api.dto.SeleccionResponse;
import ar.correoargentino.impoexpo.client.CivuceClient;
import ar.correoargentino.impoexpo.client.CivuceClient.CivuceItem;
import ar.correoargentino.impoexpo.client.CivuceClient.DetallePosicion;
import ar.correoargentino.impoexpo.client.CivuceClient.TextoPartida;
import ar.correoargentino.impoexpo.client.OpenRouterClient;
import ar.correoargentino.impoexpo.config.AppProperties;
import ar.correoargentino.impoexpo.service.HistorialSelecciones.Match;
import ar.correoargentino.impoexpo.service.PosicionScorer.MappedPosicion;
import ar.correoargentino.impoexpo.service.PosicionScorer.ScoredItem;
import ar.correoargentino.impoexpo.util.Embeddings;
import ar.correoargentino.impoexpo.util.PluralSpanish;
import ar.correoargentino.impoexpo.util.TextNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PosicionesArancelariasService {

	private static final Logger log = LoggerFactory.getLogger(PosicionesArancelariasService.class);
	private static final int MIN_RESULTADOS_LLM = 3;
	private static final int MAX_TERMINOS_PARALELO = 4;
	private static final int LONGITUD_QUERY_CORTA = 5;
	private static final int BOOST_BASE = 18;
	private static final int MAX_DETALLES_JERARQUIA = 24;
	private static final int MAX_EMBED_PARTIDAS = 40;

	private final CivuceClient civuceClient;
	private final OpenRouterClient openRouterClient;
	private final SinonimosLexico sinonimosLexico;
	private final PosicionScorer posicionScorer;
	private final UnidadMedidaCatalogo unidadMedidaCatalogo;
	private final HistorialSelecciones historial;
	private final BusquedaCache cache;
	private final AppProperties properties;
	private final ExecutorService executor;

	public PosicionesArancelariasService(
			CivuceClient civuceClient,
			OpenRouterClient openRouterClient,
			SinonimosLexico sinonimosLexico,
			PosicionScorer posicionScorer,
			UnidadMedidaCatalogo unidadMedidaCatalogo,
			HistorialSelecciones historial,
			BusquedaCache cache,
			AppProperties properties,
			@Qualifier("civuceSearchExecutor") ExecutorService executor) {
		this.civuceClient = civuceClient;
		this.openRouterClient = openRouterClient;
		this.sinonimosLexico = sinonimosLexico;
		this.posicionScorer = posicionScorer;
		this.unidadMedidaCatalogo = unidadMedidaCatalogo;
		this.historial = historial;
		this.cache = cache;
		this.properties = properties;
		this.executor = executor;
	}

	public BuscarPosicionesResponse buscar(String consulta, int limite) {
		long start = System.currentTimeMillis();
		String q = consulta == null ? "" : consulta.trim();
		if (q.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El parámetro q es obligatorio");
		}
		if (q.length() < 3) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ingresá al menos 3 caracteres");
		}

		int maxResultados = limite > 0 ? limite : properties.getCivuce().getMaxResultados();
		String cacheKey = cacheKey(q, maxResultados);
		BuscarPosicionesResponse cached = cache.<BuscarPosicionesResponse>get(cacheKey).orElse(null);
		if (cached != null) {
			return cached;
		}

		List<String> queryTokens = sinonimosLexico.tokensProducto(q);
		boolean queryCorta = esQueryCorta(q, queryTokens);
		boolean umbralEstricto = queryCorta;
		boolean fuenteLlm = false;

		List<String> searchTerms = new ArrayList<>(sinonimosLexico.terminosDeBusqueda(q));
		List<String> relevanceTokens = new ArrayList<>(queryTokens);
		if (relevanceTokens.isEmpty()) {
			relevanceTokens.addAll(List.of(TextNormalizer.tokens(q)));
		}

		if (queryCorta && openRouterClient.configured()) {
			try {
				InterpretacionBusqueda interpretacion = openRouterClient.interpretar(q);
				if (interpretacion.tieneTerminos()) {
					fuenteLlm = true;
					searchTerms = priorizarTerminos(interpretacion.terminosBusqueda(), q);
					if (!interpretacion.tokensRelevancia().isEmpty()) {
						LinkedHashSet<String> merged = new LinkedHashSet<>(queryTokens);
						merged.addAll(interpretacion.tokensRelevancia());
						relevanceTokens = new ArrayList<>(merged);
					}
				}
			} catch (OpenRouterException ex) {
				if (searchTerms.isEmpty()) {
					throw ex;
				}
			}
		}

		List<String> synonymTokens = tokensSinonimo(searchTerms, relevanceTokens);
		Set<String> terminosUsados = new LinkedHashSet<>(searchTerms);
		CompletableFuture<double[]> embeddingFuture = CompletableFuture.supplyAsync(
				() -> openRouterClient.embed(q), executor);
		List<ScoredItem> crudos = recolectarParalelo(searchTerms, fuenteLlm);
		double[] embedding = embeddingFuture.join();
		List<Match> historialHits = historial.similares(q, embedding);

		List<MappedPosicion> posiciones = procesarCrudos(
				crudos, relevanceTokens, synonymTokens, historialHits, embedding, maxResultados, umbralEstricto);

		if (!fuenteLlm && posiciones.size() < MIN_RESULTADOS_LLM && openRouterClient.configured()) {
			try {
				InterpretacionBusqueda extra = openRouterClient.interpretar(q);
				List<String> nuevos = extra.terminosBusqueda().stream()
						.filter(t -> !terminosUsados.contains(t))
						.toList();
				if (!nuevos.isEmpty()) {
					fuenteLlm = true;
					terminosUsados.addAll(nuevos);
					searchTerms.addAll(nuevos);
					crudos = mergeCrudos(crudos, recolectarParalelo(nuevos, true));
					synonymTokens = tokensSinonimo(searchTerms, relevanceTokens);
					posiciones = procesarCrudos(
							crudos, relevanceTokens, synonymTokens, historialHits, embedding, maxResultados, umbralEstricto);
				}
			} catch (OpenRouterException ex) {
				if (posiciones.isEmpty()) {
					throw ex;
				}
			}
		}

		posiciones = inyectarHistorial(posiciones, historialHits);

		if (posiciones.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"CIVUCE no encontró posiciones relevantes para «" + q + "».");
		}

		boolean boostHistorial = posiciones.stream().anyMatch(MappedPosicion::boostHistorial);
		String fase = fuenteLlm ? "completa" : "rapida";
		BuscarPosicionesResponse response = new BuscarPosicionesResponse(
				q,
				fase,
				List.copyOf(terminosUsados),
				fuenteLlm,
				boostHistorial,
				agruparParaRespuesta(posiciones),
				properties.getAvisoLegal(),
				requestId(),
				System.currentTimeMillis() - start);
		cache.put(cacheKey, response);
		return response;
	}

	public DetallePosicionResponse detalle(String codigo) {
		if (codigo == null || codigo.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código requerido");
		}
		DetallePosicion det = civuceClient.obtenerPosicion(codigo.trim());
		return mapDetalle(det);
	}

	public SeleccionResponse registrarSeleccion(String consulta, String codigoSim) {
		if (consulta == null || consulta.isBlank() || codigoSim == null || codigoSim.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "consulta y codigoSim son obligatorios");
		}
		DetallePosicion det = civuceClient.obtenerPosicion(codigoSim.trim());
		double[] embedding = openRouterClient.embed(consulta.trim());
		historial.registrar(consulta.trim(), det.posicion(), det.unidad(), embedding);
		cache.invalidate();
		return new SeleccionResponse(
				true,
				consulta.trim(),
				det.posicion(),
				det.unidad(),
				unidadMedidaCatalogo.resolverNombre(det.unidad()),
				requestId());
	}

	private DetallePosicionResponse mapDetalle(DetallePosicion det) {
		String ncmBase = posicionScorer.ncmBase(det.posicion());
		String descripcion = det.descripcion();
		if (det.descripcionCompleta() != null && !det.descripcionCompleta().isEmpty()) {
			for (int i = det.descripcionCompleta().size() - 1; i >= 0; i--) {
				var nivel = det.descripcionCompleta().get(i);
				if (nivel.descripcion() != null && nivel.descripcion().length() > 5) {
					descripcion = TextNormalizer.limpiarHtml(nivel.descripcion());
					if (nivel.posicion() != null && nivel.posicion().matches("\\d{2}\\.\\d{2}.*")) {
						break;
					}
				}
			}
		}
		List<NivelJerarquia> jerarquia = det.descripcionCompleta() == null
				? List.of()
				: det.descripcionCompleta().stream()
						.map(n -> new NivelJerarquia(n.posicion(), TextNormalizer.limpiarHtml(n.descripcion())))
						.toList();
		return new DetallePosicionResponse(
				ncmBase,
				det.posicion(),
				descripcion,
				det.unidad(),
				unidadMedidaCatalogo.resolverNombre(det.unidad()),
				det.derechosExportacion(),
				jerarquia,
				properties.getAvisoLegal(),
				requestId());
	}

	private List<MappedPosicion> procesarCrudos(
			List<ScoredItem> crudos,
			List<String> relevanceTokens,
			List<String> synonymTokens,
			List<Match> historialHits,
			double[] queryEmbedding,
			int maxResultados,
			boolean umbralEstricto) {
		List<ScoredItem> preliminares = crudos.stream()
				.map(s -> puntuarItem(s, relevanceTokens, synonymTokens, List.of()))
				.toList();
		List<ScoredItem> scored = enriquecerConDetalle(preliminares).stream()
				.map(s -> puntuarItem(s, relevanceTokens, synonymTokens, historialHits))
				.toList();
		scored = aplicarRerankEmbedding(scored, queryEmbedding);
		return posicionScorer.procesar(scored, maxResultados, umbralEstricto);
	}

	/**
	 * Re-ranking semántico por partida (6 dígitos): un vector del encabezado NCM, no de la hoja.
	 * Si OpenRouter falla, no altera el puntaje léxico.
	 */
	private List<ScoredItem> aplicarRerankEmbedding(List<ScoredItem> scored, double[] queryEmbedding) {
		if (queryEmbedding == null || queryEmbedding.length == 0 || scored.isEmpty()) {
			return scored;
		}
		LinkedHashMap<String, String> textoPorPartida = new LinkedHashMap<>();
		LinkedHashMap<String, Integer> scorePorPartida = new LinkedHashMap<>();
		List<ScoredItem> ordenados = new ArrayList<>(scored);
		ordenados.sort(Comparator
				.comparingInt(ScoredItem::rawScore).reversed()
				.thenComparing(s -> PosicionScorer.esPartes(s.item().descripcion())));
		for (ScoredItem s : ordenados) {
			String partida = posicionScorer.ncmPartidaMatch(s.item().posicion());
			if (partida.isBlank()) {
				continue;
			}
			String texto = posicionScorer.textoEncabezadoParaEmbedding(s.item());
			if (texto.isBlank()) {
				continue;
			}
			int prev = scorePorPartida.getOrDefault(partida, Integer.MIN_VALUE);
			boolean mejorTexto = !PosicionScorer.esPartes(s.item().descripcion())
					&& PosicionScorer.esPartes(textoPorPartida.getOrDefault(partida, ""));
			if (!textoPorPartida.containsKey(partida) || s.rawScore() > prev || mejorTexto) {
				if (textoPorPartida.size() >= MAX_EMBED_PARTIDAS && !textoPorPartida.containsKey(partida)) {
					continue;
				}
				textoPorPartida.put(partida, texto);
				scorePorPartida.put(partida, s.rawScore());
			}
		}
		if (textoPorPartida.isEmpty()) {
			return scored;
		}
		List<String> partidas = new ArrayList<>(textoPorPartida.keySet());
		List<String> textos = partidas.stream().map(textoPorPartida::get).toList();
		List<double[]> vectores = openRouterClient.embedAll(textos);
		Map<String, Double> cosinePorPartida = new LinkedHashMap<>();
		for (int i = 0; i < partidas.size(); i++) {
			double[] vec = i < vectores.size() ? vectores.get(i) : new double[0];
			cosinePorPartida.put(partidas.get(i), Embeddings.cosine(queryEmbedding, vec));
		}
		return posicionScorer.aplicarEmbeddingPartidas(scored, cosinePorPartida);
	}

	private ScoredItem puntuarItem(
			ScoredItem s,
			List<String> relevanceTokens,
			List<String> synonymTokens,
			List<Match> historialHits) {
		var result = posicionScorer.puntuar(s.item(), relevanceTokens, synonymTokens, s.termino());
		Match hit = matchHistorial(s.item().posicion(), historialHits);
		int raw = result.rawScore();
		boolean boost = false;
		if (hit != null) {
			raw += (int) Math.round(BOOST_BASE * hit.cosine() * Math.log1p(hit.frecuencia()));
			boost = true;
		}
		return new ScoredItem(
				s.item(),
				s.termino(),
				raw,
				result.matchPalabraCompleta(),
				s.sugeridoPorIa(),
				boost);
	}

	/**
	 * El listado {@code /cice/posicionesTexto} deja {@code texto_partida} en null en muchas
	 * hojas. {@code GET /cice/posicion/{codigo}} sí trae {@code descripcion_completa} (jerarquía
	 * NCM) y {@code texto_partida} concatenado. Un fetch por subpartida de 8 dígitos.
	 */
	private List<ScoredItem> enriquecerConDetalle(List<ScoredItem> crudos) {
		LinkedHashMap<String, String> subpartidaToSim = new LinkedHashMap<>();
		List<ScoredItem> ordenados = new ArrayList<>(crudos);
		ordenados.sort(Comparator.comparingInt(ScoredItem::rawScore).reversed());
		for (ScoredItem scored : ordenados) {
			CivuceItem item = scored.item();
			if (item.posicion() == null || !item.textoPartidaPlano().isBlank()) {
				continue;
			}
			String sub = posicionScorer.ncmSubpartida(item.posicion());
			if (sub.isBlank()) {
				continue;
			}
			if (subpartidaToSim.size() >= MAX_DETALLES_JERARQUIA && !subpartidaToSim.containsKey(sub)) {
				continue;
			}
			subpartidaToSim.putIfAbsent(sub, item.posicion());
		}
		if (subpartidaToSim.isEmpty()) {
			return crudos;
		}

		String requestId = requestId();
		Map<String, DetallePosicion> porSubpartida = new ConcurrentHashMap<>();
		List<CompletableFuture<Void>> futures = subpartidaToSim.entrySet().stream()
				.map(entry -> CompletableFuture.runAsync(() -> {
					MDC.put("requestId", requestId);
					try {
						porSubpartida.put(entry.getKey(), civuceClient.obtenerPosicion(entry.getValue()));
					} catch (Exception ex) {
						log.warn("CIVUCE detalle {} falló: {}", entry.getValue(), ex.getMessage());
					} finally {
						MDC.remove("requestId");
					}
				}, executor))
				.toList();
		CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
		log.info("CIVUCE jerarquía: {} subpartidas enriquecidas de {} pedidas",
				porSubpartida.size(), subpartidaToSim.size());

		List<ScoredItem> out = new ArrayList<>();
		for (ScoredItem scored : crudos) {
			CivuceItem item = scored.item();
			DetallePosicion det = porSubpartida.get(posicionScorer.ncmSubpartida(item.posicion()));
			if (det == null) {
				out.add(scored);
				continue;
			}
			List<CivuceClient.NivelJerarquia> jerarquia = jerarquiaAplicable(item.posicion(), det);
			String texto = textoDesdeJerarquia(jerarquia);
			if (texto.isBlank()) {
				texto = det.textoPartida() == null ? "" : det.textoPartida();
			}
			out.add(new ScoredItem(
					item.conJerarquia(texto, jerarquia),
					scored.termino(),
					scored.rawScore(),
					scored.matchPalabraCompleta(),
					scored.sugeridoPorIa(),
					scored.boostHistorial()));
		}
		return out;
	}

	private static List<CivuceClient.NivelJerarquia> jerarquiaAplicable(String codigoSim, DetallePosicion det) {
		if (det.descripcionCompleta() != null && !det.descripcionCompleta().isEmpty()) {
			return det.descripcionCompleta().stream()
					.filter(n -> esAncestroNcm(n.posicion(), codigoSim))
					.toList();
		}
		if (det.descripcion() != null && !det.descripcion().isBlank()) {
			return List.of(new CivuceClient.NivelJerarquia(det.posicion(), det.descripcion()));
		}
		return List.of();
	}

	private static String textoDesdeJerarquia(List<CivuceClient.NivelJerarquia> jerarquia) {
		if (jerarquia == null || jerarquia.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (CivuceClient.NivelJerarquia nivel : jerarquia) {
			if (nivel == null || nivel.descripcion() == null || nivel.descripcion().isBlank()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(nivel.descripcion());
		}
		return sb.toString();
	}

	static boolean esAncestroNcm(String nivelPos, String codigoSim) {
		if (nivelPos == null || codigoSim == null) {
			return false;
		}
		String a = nivelPos.replaceAll("\\D", "");
		String b = codigoSim.replaceAll("\\D", "");
		return !a.isEmpty() && !b.isEmpty() && b.startsWith(a);
	}

	private List<MappedPosicion> inyectarHistorial(List<MappedPosicion> actuales, List<Match> hits) {
		if (hits.isEmpty()) {
			return actuales;
		}
		Set<String> presentes = new LinkedHashSet<>();
		for (MappedPosicion p : actuales) {
			presentes.add(p.codigoSim());
		}
		List<MappedPosicion> out = new ArrayList<>(actuales);
		int extras = 0;
		int maxExtras = 3;
		for (Match hit : hits) {
			if (presentes.contains(hit.codigoSim())) {
				continue;
			}
			if (extras >= maxExtras) {
				break;
			}
			try {
				DetallePosicion det = civuceClient.obtenerPosicion(hit.codigoSim());
				CivuceItem item = new CivuceItem(
						det.posicion(),
						det.descripcion(),
						det.unidad(),
						det.derechosExportacion(),
						det.derechosExportacion(),
						null,
						new TextoPartida(det.posicion(), det.textoPartida()),
						det.posPadre(),
						det.descripcionCompleta());
				double conf = Math.min(0.99, 0.7 + hit.cosine() * 0.25);
				out.add(0, new MappedPosicion(
						det.posicion(),
						posicionScorer.ncmBase(det.posicion()),
						posicionScorer.ncmBase(det.posicion()),
						TextNormalizer.limpiarHtml(
								det.textoPartida() != null && det.textoPartida().length() > 10
										? det.textoPartida().substring(0, Math.min(180, det.textoPartida().length()))
										: det.descripcion()),
						posicionScorer.extraerGrupo(item),
						conf,
						hit.consultaOrigen(),
						det.unidad(),
						false,
						true,
						true));
				presentes.add(det.posicion());
				extras++;
			} catch (Exception ignored) {
				// Si CIVUCE ya no tiene el código, no lo inyectamos.
			}
		}
		return out;
	}

	private static Match matchHistorial(String codigo, List<Match> hits) {
		if (codigo == null) {
			return null;
		}
		for (Match m : hits) {
			if (codigo.equalsIgnoreCase(m.codigoSim())) {
				return m;
			}
		}
		return null;
	}

	private List<ScoredItem> recolectarParalelo(List<String> terminos, boolean sugeridoPorIa) {
		List<String> aConsultar = compactarTerminos(terminos).stream().limit(MAX_TERMINOS_PARALELO).toList();
		String requestId = requestId();
		List<CompletableFuture<List<ScoredItem>>> futures = aConsultar.stream()
				.map(termino -> CompletableFuture.supplyAsync(() -> {
					MDC.put("requestId", requestId);
					try {
						List<CivuceItem> items = civuceClient.recolectarPorTermino(termino);
						return items.stream()
								.map(item -> new ScoredItem(item, termino, 0, false, sugeridoPorIa, false))
								.toList();
					} finally {
						MDC.remove("requestId");
					}
				}, executor))
				.toList();
		List<ScoredItem> out = new ArrayList<>();
		for (CompletableFuture<List<ScoredItem>> f : futures) {
			out.addAll(f.join());
		}
		return out;
	}

	private List<ScoredItem> mergeCrudos(List<ScoredItem> a, List<ScoredItem> b) {
		List<ScoredItem> merged = new ArrayList<>(a);
		merged.addAll(b);
		return merged;
	}

	private List<GrupoPosiciones> agruparParaRespuesta(List<MappedPosicion> posiciones) {
		LinkedHashMap<String, List<ItemPosicion>> map = new LinkedHashMap<>();
		for (MappedPosicion p : posiciones) {
			ItemPosicion item = new ItemPosicion(
					p.codigoNcm(),
					p.codigoSim(),
					p.descripcion(),
					p.confianza(),
					p.unidadCodigo(),
					unidadMedidaCatalogo.resolverNombre(p.unidadCodigo()),
					p.sugeridoPorIa(),
					p.boostHistorial());
			map.computeIfAbsent(p.grupo(), k -> new ArrayList<>()).add(item);
		}
		return map.entrySet().stream()
				.map(e -> new GrupoPosiciones(e.getKey(), List.copyOf(e.getValue())))
				.toList();
	}

	private List<String> priorizarTerminos(List<String> terms, String consulta) {
		LinkedHashSet<String> out = new LinkedHashSet<>(sinonimosLexico.terminosDeBusqueda(consulta));
		for (String term : terms) {
			String v = TextNormalizer.normalizar(term);
			if (v.length() >= 3) {
				out.add(v);
			}
		}
		for (String term : new ArrayList<>(out)) {
			for (String tok : TextNormalizer.tokens(term)) {
				for (String variant : PluralSpanish.variantes(tok)) {
					if (variant.length() >= 3 && !SinonimosLexico.esTerminoGenerico(variant)) {
						out.add(variant);
					}
				}
			}
		}
		return out.stream().limit(MAX_TERMINOS_PARALELO).toList();
	}

	private static boolean esQueryCorta(String q, List<String> queryTokens) {
		return q.length() <= LONGITUD_QUERY_CORTA || queryTokens.size() <= 1;
	}

	private static List<String> compactarTerminos(List<String> terminos) {
		List<String> distinct = terminos.stream()
				.map(TextNormalizer::normalizar)
				.filter(t -> t.length() >= 3)
				.distinct()
				.toList();
		List<String> out = new ArrayList<>();
		for (String t : distinct) {
			boolean cubierto = distinct.stream().anyMatch(o -> !o.equals(t) && (
					o.startsWith(t + " ")
					|| o.endsWith(" " + t)
					|| o.contains(" " + t + " ")
					|| (t.indexOf(' ') < 0 && o.startsWith(t) && o.length() > t.length()
							&& Character.isLetter(o.charAt(t.length())))));
			if (!cubierto) {
				out.add(t);
			}
		}
		return out;
	}

	private static List<String> tokensSinonimo(List<String> searchTerms, List<String> relevanceTokens) {
		Set<String> relevancia = new LinkedHashSet<>(relevanceTokens);
		List<String> out = new ArrayList<>();
		for (String term : searchTerms) {
			for (String tok : TextNormalizer.tokens(term)) {
				if (relevancia.contains(tok) || out.contains(tok) || SinonimosLexico.esTerminoGenerico(tok)) {
					continue;
				}
				out.add(tok);
			}
		}
		return out;
	}

	private static String cacheKey(String q, int limite) {
		return TextNormalizer.normalizar(q) + "|l=" + limite;
	}

	private static String requestId() {
		String id = MDC.get("requestId");
		return id == null ? "" : id;
	}
}
