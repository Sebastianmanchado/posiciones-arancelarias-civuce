package ar.correoargentino.impoexpo.service;

import ar.correoargentino.impoexpo.client.CivuceClient.CivuceItem;
import ar.correoargentino.impoexpo.util.PluralSpanish;
import ar.correoargentino.impoexpo.util.TextNormalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PosicionScorer {

	private static final int PUNTOS_PALABRA_COMPLETA = 12;
	private static final int PUNTOS_SUBSTRING = 4;
	private static final int PUNTOS_SINONIMO_PALABRA = 8;
	private static final int PUNTOS_SINONIMO_SUBSTRING = 3;
	private static final int PUNTOS_HOJA_PRODUCTO = 8;
	private static final int PUNTOS_PARTES = 16;
	private static final int PUNTOS_EMBEDDING_MAX = 14;
	private static final double UMBRAL_COSINE_EMBEDDING = 0.40;

	private static final Pattern ENCABEZADO_NCM = Pattern.compile(
			"([A-ZÁÉÍÓÚÜÑ][A-ZÁÉÍÓÚÜÑ0-9 ,;()/\\-]{19,})");

	private static final double UMBRAL_RELAXADO = 0.35;
	private static final double UMBRAL_ESTRICTO = 0.50;

	public record ScoredItem(
			CivuceItem item,
			String termino,
			int rawScore,
			boolean matchPalabraCompleta,
			boolean sugeridoPorIa,
			boolean boostHistorial) {
	}

	public record MappedPosicion(
			String codigoSim,
			String codigoNcm,
			String ncmBase,
			String descripcion,
			String grupo,
			double confianza,
			String termino,
			String unidadCodigo,
			boolean sugeridoPorIa,
			boolean matchPalabraCompleta,
			boolean boostHistorial) {
	}

	public record ScoreResult(int rawScore, boolean matchPalabraCompleta) {
	}

	public List<MappedPosicion> procesar(
			List<ScoredItem> crudos,
			int maxResultados,
			boolean umbralEstricto) {

		List<ScoredItem> scored = crudos.stream()
				.filter(x -> x.item().posicion() != null && !x.item().posicion().isBlank())
				.toList();

		if (scored.isEmpty()) {
			return List.of();
		}

		Map<String, ScoredItem> unicos = new LinkedHashMap<>();
		for (ScoredItem x : scored) {
			String sim = x.item().posicion();
			ScoredItem prev = unicos.get(sim);
			if (prev == null || mejorCrudo(x, prev)) {
				unicos.put(sim, x);
			}
		}

		Map<String, List<ScoredItem>> porPartida = new LinkedHashMap<>();
		for (ScoredItem x : unicos.values()) {
			porPartida.computeIfAbsent(ncmPartidaMatch(x.item().posicion()), k -> new ArrayList<>()).add(x);
		}

		record Heading(String clave, int maxRaw, boolean palabraCompleta, boolean boost) {
		}
		List<Heading> headings = new ArrayList<>();
		for (Map.Entry<String, List<ScoredItem>> e : porPartida.entrySet()) {
			int maxRaw = e.getValue().stream().mapToInt(ScoredItem::rawScore).max().orElse(0);
			boolean palabra = e.getValue().stream().anyMatch(ScoredItem::matchPalabraCompleta);
			boolean boost = e.getValue().stream().anyMatch(ScoredItem::boostHistorial);
			headings.add(new Heading(e.getKey(), maxRaw, palabra, boost));
		}

		double umbralFactor = umbralEstricto ? UMBRAL_ESTRICTO : UMBRAL_RELAXADO;
		List<Heading> candidatos = headings;
		if (umbralEstricto && headings.stream().anyMatch(Heading::palabraCompleta)) {
			candidatos = headings.stream().filter(Heading::palabraCompleta).toList();
		}
		int maxRaw = candidatos.stream().mapToInt(Heading::maxRaw).max().orElse(1);
		int umbral = Math.max(4, (int) Math.round(maxRaw * umbralFactor));
		List<Heading> elegidas = candidatos.stream()
				.filter(h -> h.maxRaw() >= umbral)
				.sorted(Comparator
						.comparing(Heading::boost, Comparator.reverseOrder())
						.thenComparing(Heading::palabraCompleta, Comparator.reverseOrder())
						.thenComparing(Comparator.comparingInt(Heading::maxRaw).reversed()))
				.limit(Math.max(1, maxResultados))
				.toList();
		if (elegidas.isEmpty()) {
			elegidas = candidatos.stream()
					.sorted(Comparator.comparingInt(Heading::maxRaw).reversed())
					.limit(Math.min(3, candidatos.size()))
					.toList();
		}

		int maxForConf = headings.stream().mapToInt(Heading::maxRaw).max().orElse(1);
		List<MappedPosicion> mapped = new ArrayList<>();
		for (Heading heading : elegidas) {
			List<ScoredItem> hojas = new ArrayList<>(porPartida.getOrDefault(heading.clave(), List.of()));
			hojas.sort(Comparator.comparing(x -> x.item().posicion()));
			for (ScoredItem x : hojas) {
				double confianza = Math.min(0.97, Math.max(0.35,
						0.4 + ((double) Math.max(x.rawScore(), heading.maxRaw()) / Math.max(1, maxForConf)) * 0.55));
				if (x.matchPalabraCompleta() || heading.palabraCompleta()) {
					confianza = Math.min(0.97, confianza + 0.05);
				}
				if (x.boostHistorial()) {
					confianza = Math.min(0.99, confianza + 0.08);
				}
				mapped.add(mapItem(x, confianza));
			}
		}
		return mapped;
	}

	public ScoreResult puntuar(
			CivuceItem item,
			List<String> relevanceTokens,
			List<String> synonymTokens,
			String terminoRecoleccion) {
		String blob = textoItem(item);
		Set<String> tokensItem = new HashSet<>(Arrays.asList(TextNormalizer.tokens(blob)));
		int score = 0;
		boolean palabraCompleta = false;

		if (!blob.isBlank()) {
			for (String tok : relevanceTokens) {
				if (tok == null || tok.isBlank()) {
					continue;
				}
				if (PluralSpanish.coincidePalabraCompleta(tokensItem, tok)) {
					score += PUNTOS_PALABRA_COMPLETA;
					palabraCompleta = true;
				} else if (blob.contains(tok)) {
					score += PUNTOS_SUBSTRING;
				}
			}
			for (String syn : synonymTokens) {
				if (syn == null || syn.isBlank() || relevanceTokens.contains(syn)) {
					continue;
				}
				if (PluralSpanish.coincidePalabraCompleta(tokensItem, syn)) {
					score += PUNTOS_SINONIMO_PALABRA;
					palabraCompleta = true;
				} else if (blob.contains(syn)) {
					score += PUNTOS_SINONIMO_SUBSTRING;
				}
			}
		}

		if (terminoAporta(terminoRecoleccion, relevanceTokens, synonymTokens)
				&& (esHojaDebil(item.descripcion()) || TextNormalizer.tokens(terminoRecoleccion).length >= 2
						|| esSinonimoMasEspecifico(terminoRecoleccion, relevanceTokens))) {
			score += PUNTOS_PALABRA_COMPLETA;
			palabraCompleta = true;
		}

		if (esPartes(item.descripcion())) {
			score -= PUNTOS_PARTES;
		} else if (esHojaDebil(item.descripcion()) && !textoItem(item).isBlank()
				&& !TextNormalizer.normalizar(item.descripcion()).equals(textoItem(item))) {
			score += PUNTOS_HOJA_PRODUCTO;
		}

		return new ScoreResult(Math.max(0, score), palabraCompleta);
	}

	/** Coseno consulta↔encabezado de partida. Debajo de 0.40 no suma (ruido). */
	public int puntosEmbedding(double cosine) {
		if (cosine < UMBRAL_COSINE_EMBEDDING) {
			return 0;
		}
		return (int) Math.round(PUNTOS_EMBEDDING_MAX * Math.min(1.0, cosine));
	}

	/**
	 * Texto canónico de la partida (4/6 dígitos), no la hoja de capacidad ni el blob de «Partes».
	 */
	public String textoEncabezadoParaEmbedding(CivuceItem item) {
		String heading = headingDesdeJerarquia(item);
		if (!heading.isBlank()) {
			return heading;
		}
		String extraido = extraerEncabezadoProducto(TextNormalizer.limpiarHtml(item.textoPartidaPlano()));
		if (!extraido.isBlank() && !esPartes(extraido) && !esHojaDebil(extraido)) {
			return extraido;
		}
		return "";
	}

	public List<ScoredItem> aplicarEmbeddingPartidas(
			List<ScoredItem> scored,
			Map<String, Double> cosinePorPartida) {
		if (scored == null || scored.isEmpty() || cosinePorPartida == null || cosinePorPartida.isEmpty()) {
			return scored == null ? List.of() : scored;
		}
		List<ScoredItem> out = new ArrayList<>();
		for (ScoredItem s : scored) {
			double cosine = cosinePorPartida.getOrDefault(ncmPartidaMatch(s.item().posicion()), 0.0);
			int extra = puntosEmbedding(cosine);
			if (extra == 0) {
				out.add(s);
				continue;
			}
			out.add(new ScoredItem(
					s.item(),
					s.termino(),
					s.rawScore() + extra,
					s.matchPalabraCompleta(),
					s.sugeridoPorIa(),
					s.boostHistorial()));
		}
		return out;
	}

	public String extraerGrupo(CivuceItem item) {
		if (item.descripcionCompleta() != null) {
			for (var nivel : item.descripcionCompleta()) {
				if (nivel == null || nivel.descripcion() == null) {
					continue;
				}
				String digits = nivel.posicion() == null ? "" : nivel.posicion().replaceAll("\\D", "");
				if (digits.length() == 2) {
					String g = TextNormalizer.limpiarHtml(nivel.descripcion());
					if (g.length() >= 8) {
						return g;
					}
				}
			}
		}
		String texto = TextNormalizer.limpiarHtml(item.textoPartidaPlano());
		if (texto.isBlank()) {
			texto = TextNormalizer.limpiarHtml(item.descripcion());
		}
		String[] partes = texto.split("\\s{2,}|\\.");
		for (int i = partes.length - 1; i >= 0; i--) {
			String p = partes[i].trim();
			if (p.length() >= 20 && p.equals(p.toUpperCase())) {
				return p;
			}
		}
		if (item.posicion() != null && item.posicion().length() >= 2) {
			String cap = item.posicion().replaceAll("[^0-9].*", "");
			if (cap.length() >= 2) {
				return "Capítulo " + cap.substring(0, 2);
			}
		}
		return "Posiciones SIM";
	}

	public String ncmBase(String codigo) {
		String digits = codigo == null ? "" : codigo.replaceAll("\\D", "");
		if (digits.length() >= 4) {
			return digits.substring(0, 2) + "." + digits.substring(2, 4);
		}
		if (codigo == null) {
			return "";
		}
		return codigo.replaceAll("[A-Za-z]$", "").replaceAll("\\.\\d{3}[A-Za-z]?$", "");
	}

	/** Match de ranking: subpartida HS (6 dígitos) o partida (4). */
	public String ncmPartidaMatch(String codigo) {
		String digits = codigo == null ? "" : codigo.replaceAll("\\D", "");
		if (digits.length() >= 6) {
			return digits.substring(0, 4) + "." + digits.substring(4, 6);
		}
		return ncmBase(codigo);
	}

	/** Subpartida NCM de 8 dígitos (sin sufijo SIM). */
	public String ncmSubpartida(String codigo) {
		String digits = codigo == null ? "" : codigo.replaceAll("\\D", "");
		if (digits.length() >= 8) {
			return digits.substring(0, 4) + "." + digits.substring(4, 6) + "." + digits.substring(6, 8);
		}
		if (digits.length() >= 6) {
			return digits.substring(0, 4) + "." + digits.substring(4, 6);
		}
		return ncmBase(codigo);
	}

	private MappedPosicion mapItem(ScoredItem scored, double confianza) {
		CivuceItem item = scored.item();
		String codigo = item.posicion();
		String descripcion = descripcionVisible(item, scored.termino());
		String subpartida = ncmSubpartida(codigo);
		String grupo = headingDesdeJerarquia(item);
		if (grupo.isBlank()) {
			grupo = extraerGrupo(item);
		}
		return new MappedPosicion(
				codigo,
				subpartida,
				ncmBase(codigo),
				descripcion,
				grupo,
				confianza,
				scored.termino(),
				item.unidad(),
				scored.sugeridoPorIa(),
				scored.matchPalabraCompleta(),
				scored.boostHistorial());
	}

	private String descripcionVisible(CivuceItem item, String termino) {
		String descCorta = TextNormalizer.limpiarHtml(item.descripcion());
		String heading = headingDesdeJerarquia(item);
		if (heading.isBlank()) {
			heading = extraerEncabezadoProducto(TextNormalizer.limpiarHtml(item.textoPartidaPlano()));
		}
		if (!heading.isBlank()) {
			if (descCorta.isBlank() || heading.toLowerCase().contains(descCorta.toLowerCase())) {
				return heading;
			}
			return heading + " — " + descCorta;
		}
		if (esHojaDebil(descCorta) && termino != null && !termino.isBlank()) {
			return capitalizar(termino) + (descCorta.isBlank() ? "" : " — " + descCorta);
		}
		String partidaTxt = TextNormalizer.limpiarHtml(item.textoPartidaPlano());
		if (!partidaTxt.isBlank() && partidaTxt.length() > 10 && !esHojaDebil(partidaTxt)) {
			return partidaTxt.substring(0, Math.min(180, partidaTxt.length()));
		}
		return descCorta.isBlank() ? "Sin descripción" : descCorta;
	}

	/** Nivel NCM más específico que no sea hoja débil ni «Partes» (viene de descripcion_completa). */
	String headingDesdeJerarquia(CivuceItem item) {
		if (item.descripcionCompleta() == null || item.descripcionCompleta().isEmpty()) {
			return "";
		}
		for (int i = item.descripcionCompleta().size() - 1; i >= 0; i--) {
			var nivel = item.descripcionCompleta().get(i);
			if (nivel == null || nivel.descripcion() == null) {
				continue;
			}
			String digits = nivel.posicion() == null ? "" : nivel.posicion().replaceAll("\\D", "");
			if (digits.length() < 4) {
				continue;
			}
			String d = TextNormalizer.limpiarHtml(nivel.descripcion());
			if (d.length() >= 8 && !esHojaDebil(d) && !esPartes(d)) {
				return d.length() > 160 ? d.substring(0, 160) : d;
			}
		}
		return "";
	}

	private static String extraerEncabezadoProducto(String partida) {
		if (partida == null || partida.isBlank()) {
			return "";
		}
		String collapsed = partida.replaceAll("\\s+", " ").trim();
		Matcher matcher = ENCABEZADO_NCM.matcher(collapsed);
		String mejor = "";
		while (matcher.find()) {
			String g = matcher.group(1).trim();
			if (esPartes(g) || esHojaDebil(g)) {
				continue;
			}
			if (g.length() > mejor.length()) {
				mejor = g;
			}
		}
		if (!mejor.isBlank()) {
			return mejor.length() > 160 ? mejor.substring(0, 160) : mejor;
		}
		String[] chunks = collapsed.split("\\s{2,}|\\.(?=\\s)");
		for (String chunk : chunks) {
			String p = chunk.trim();
			if (p.length() < 12 || esHojaDebil(p) || esPartes(p)) {
				continue;
			}
			if (p.equals(p.toUpperCase())) {
				return p.length() > 160 ? p.substring(0, 160) : p;
			}
		}
		return "";
	}

	private static boolean terminoAporta(
			String terminoRecoleccion,
			List<String> relevanceTokens,
			List<String> synonymTokens) {
		if (terminoRecoleccion == null || terminoRecoleccion.isBlank()) {
			return false;
		}
		Set<String> tokensTerm = new HashSet<>(Arrays.asList(TextNormalizer.tokens(terminoRecoleccion)));
		for (String tok : relevanceTokens) {
			if (PluralSpanish.coincidePalabraCompleta(tokensTerm, tok)) {
				return true;
			}
		}
		for (String syn : synonymTokens) {
			if (PluralSpanish.coincidePalabraCompleta(tokensTerm, syn)) {
				return true;
			}
		}
		return false;
	}

	private static boolean esSinonimoMasEspecifico(String terminoRecoleccion, List<String> relevanceTokens) {
		String t = TextNormalizer.normalizar(terminoRecoleccion);
		for (String tok : relevanceTokens) {
			if (t.equals(tok)) {
				return false;
			}
			if (PluralSpanish.variantes(tok).contains(t) && t.length() > tok.length()) {
				return true;
			}
		}
		return false;
	}

	static boolean esPartes(String texto) {
		String n = TextNormalizer.normalizar(texto);
		return n.startsWith("partes") || n.startsWith("parte ");
	}

	static String partida4(String codigo) {
		String digits = codigo == null ? "" : codigo.replaceAll("\\D", "");
		return digits.length() >= 4 ? digits.substring(0, 4) : "";
	}

	static boolean esHojaDebil(String texto) {
		String n = TextNormalizer.normalizar(texto);
		if (n.isBlank()) {
			return true;
		}
		return esPartes(texto) || n.equals("las demas") || n.equals("los demas")
				|| n.startsWith("con una capacidad") || n.startsWith("de capacidad");
	}

	private static String capitalizar(String s) {
		String t = s == null ? "" : s.trim();
		if (t.isEmpty()) {
			return t;
		}
		return Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}

	private static boolean mejorCrudo(ScoredItem candidate, ScoredItem prev) {
		if (candidate.boostHistorial() != prev.boostHistorial()) {
			return candidate.boostHistorial();
		}
		if (candidate.matchPalabraCompleta() != prev.matchPalabraCompleta()) {
			return candidate.matchPalabraCompleta();
		}
		return candidate.rawScore() > prev.rawScore();
	}

	String textoItem(CivuceItem item) {
		return TextNormalizer.normalizar(
				(item.descripcion() == null ? "" : item.descripcion()) + " "
						+ item.textoJerarquia() + " "
						+ item.textoPartidaPlano() + " "
						+ (item.posicion() == null ? "" : item.posicion()));
	}
}
