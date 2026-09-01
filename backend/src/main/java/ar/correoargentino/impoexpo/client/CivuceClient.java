package ar.correoargentino.impoexpo.client;

import ar.correoargentino.impoexpo.config.AppProperties;
import ar.correoargentino.impoexpo.service.CivuceUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class CivuceClient {

	private static final Logger log = LoggerFactory.getLogger(CivuceClient.class);
	private static final long TOKEN_TTL_SECONDS = 50 * 60;

	private final RestClient restClient;
	private final AppProperties properties;
	private final ObjectMapper objectMapper;
	private volatile String cachedToken;
	private volatile Instant tokenFetchedAt;

	public CivuceClient(
			@Qualifier("civuceRestClient") RestClient restClient,
			AppProperties properties,
			ObjectMapper objectMapper) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public record HealthPing(boolean reachable, String detalle) {
	}

	public record PosicionesPage(List<CivuceItem> data, int total) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CivuceItem(
			String posicion,
			String descripcion,
			String unidad,
			String derechosExportacion,
			@JsonProperty("derechos_exportacion") String derechosExportacionSnake,
			String actualizado,
			@JsonProperty("texto_partida") TextoPartida textoPartida,
			@JsonProperty("pos_padre") String posPadre,
			@JsonProperty("descripcion_completa") List<NivelJerarquia> descripcionCompleta) {

		public String derechoExportacion() {
			return derechosExportacion != null ? derechosExportacion : derechosExportacionSnake;
		}

		public String textoPartidaPlano() {
			if (textoPartida == null || textoPartida.textoPartida() == null) {
				return "";
			}
			return textoPartida.textoPartida();
		}

		public String textoJerarquia() {
			if (descripcionCompleta == null || descripcionCompleta.isEmpty()) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			for (NivelJerarquia nivel : descripcionCompleta) {
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

		public CivuceItem conJerarquia(String textoPartidaPlano, List<NivelJerarquia> jerarquia) {
			TextoPartida tp = textoPartida;
			if (textoPartidaPlano != null && !textoPartidaPlano.isBlank()) {
				tp = new TextoPartida(posicion, textoPartidaPlano);
			}
			return new CivuceItem(
					posicion,
					descripcion,
					unidad,
					derechosExportacion,
					derechosExportacionSnake,
					actualizado,
					tp,
					posPadre,
					jerarquia);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TextoPartida(
			String posicion,
			@JsonProperty("texto_partida") String textoPartida) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record DetallePosicion(
			String posicion,
			String descripcion,
			String unidad,
			@JsonProperty("derechos_exportacion") String derechosExportacion,
			@JsonProperty("descripcion_completa") List<NivelJerarquia> descripcionCompleta,
			@JsonProperty("texto_partida") String textoPartida,
			@JsonProperty("pos_padre") String posPadre) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NivelJerarquia(String posicion, String descripcion) {
	}

	public HealthPing ping() {
		try {
			getToken();
			return new HealthPing(true, "Auth OK · " + properties.getCivuce().getBaseUrl());
		} catch (Exception ex) {
			return new HealthPing(false, ex.getMessage());
		}
	}

	public PosicionesPage buscarPosicionesTexto(String texto, int page) {
		String token = getToken();
		try {
			CivuceListResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/cice/posicionesTexto")
							.queryParam("posicion", texto)
							.queryParam("operacion", "exportacion")
							.queryParam("pais", "")
							.queryParam("page", page)
							.build())
					.header("x-api-key", token)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(CivuceListResponse.class);
			if (response == null) {
				return new PosicionesPage(List.of(), 0);
			}
			List<CivuceItem> data = response.data() == null ? List.of() : response.data();
			return new PosicionesPage(data, response.total());
		} catch (RestClientException ex) {
			if (isUnauthorized(ex)) {
				invalidateToken();
				return buscarPosicionesTexto(texto, page);
			}
			log.warn("CIVUCE posicionesTexto falló para «{}» p{}: {}", texto, page, shortMsg(ex));
			if (page == 1 && isRetryable(ex)) {
				try {
					Thread.sleep(400);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
				try {
					CivuceListResponse retry = restClient.get()
							.uri(uriBuilder -> uriBuilder
									.path("/cice/posicionesTexto")
									.queryParam("posicion", texto)
									.queryParam("operacion", "exportacion")
									.queryParam("pais", "")
									.queryParam("page", page)
									.build())
							.header("x-api-key", getToken())
							.accept(MediaType.APPLICATION_JSON)
							.retrieve()
							.body(CivuceListResponse.class);
					if (retry != null && retry.data() != null && !retry.data().isEmpty()) {
						return new PosicionesPage(retry.data(), retry.total());
					}
				} catch (RestClientException ignored) {
					// se trata abajo como término vacío
				}
			}
			throw new CivuceUnavailableException("Error consultando CIVUCE: " + shortMsg(ex), ex);
		}
	}

	/** Recorre todas las páginas de CIVUCE. Si un término o una página falla, no aborta la búsqueda. */
	public List<CivuceItem> recolectarPorTermino(String termino) {
		List<CivuceItem> collected = new ArrayList<>();
		Set<String> vistos = new HashSet<>();
		int total = 0;
		int fallosSeguidos = 0;
		try {
			for (int page = 1; ; page++) {
				PosicionesPage batch;
				try {
					batch = buscarPosicionesTexto(termino, page);
					fallosSeguidos = 0;
				} catch (CivuceUnavailableException ex) {
					fallosSeguidos++;
					log.warn("CIVUCE página {} de «{}» falló ({}/3), sigo: {}",
							page, termino, fallosSeguidos, shortMsg(ex));
					if (fallosSeguidos >= 3) {
						break;
					}
					continue;
				}
				total = batch.total() > 0 ? batch.total() : total;
				if (batch.data().isEmpty()) {
					break;
				}
				int nuevos = 0;
				for (CivuceItem item : batch.data()) {
					String key = item.posicion() == null ? "" : item.posicion();
					if (!key.isBlank() && vistos.add(key)) {
						collected.add(item);
						nuevos++;
					}
				}
				if (total > 0 && collected.size() >= total) {
					break;
				}
				if (nuevos == 0 || page >= 40) {
					break;
				}
			}
		} catch (CivuceUnavailableException ex) {
			log.warn("CIVUCE no devolvió resultados para «{}»: {}", termino, shortMsg(ex));
		}
		log.info("CIVUCE «{}»: {} posiciones (total declarado {})", termino, collected.size(), total);
		return collected;
	}

	public DetallePosicion obtenerPosicion(String codigo) {
		String token = getToken();
		try {
			JsonNode root = restClient.get()
					.uri("/cice/posicion/{codigo}", codigo)
					.header("x-api-key", token)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(JsonNode.class);
			if (root == null || !root.has("data") || !root.get("data").isArray() || root.get("data").isEmpty()) {
				throw new CivuceUnavailableException("Posición no encontrada en CIVUCE: " + codigo);
			}
			JsonNode first = root.get("data").get(0);
			JsonNode detailNode = first.has("2") ? first.get("2") : first;
			return objectMapper.treeToValue(detailNode, DetallePosicion.class);
		} catch (CivuceUnavailableException ex) {
			throw ex;
		} catch (Exception ex) {
			if (ex instanceof RestClientException rce && isUnauthorized(rce)) {
				invalidateToken();
				return obtenerPosicion(codigo);
			}
			throw new CivuceUnavailableException("Error obteniendo detalle CIVUCE: " + ex.getMessage(), ex);
		}
	}

	private String getToken() {
		if (cachedToken != null && tokenFetchedAt != null
				&& tokenFetchedAt.plusSeconds(TOKEN_TTL_SECONDS).isAfter(Instant.now())) {
			return cachedToken;
		}
		synchronized (this) {
			if (cachedToken != null && tokenFetchedAt != null
					&& tokenFetchedAt.plusSeconds(TOKEN_TTL_SECONDS).isAfter(Instant.now())) {
				return cachedToken;
			}
			try {
				AuthResponse response = restClient.post()
						.uri("/auth/generate")
						.contentType(MediaType.APPLICATION_JSON)
						.body(Map.of("email", properties.getCivuce().getAuthEmail()))
						.retrieve()
						.body(AuthResponse.class);
				if (response == null || response.data() == null || response.data().isBlank()) {
					throw new CivuceUnavailableException("CIVUCE auth: respuesta sin token");
				}
				cachedToken = response.data();
				tokenFetchedAt = Instant.now();
				return cachedToken;
			} catch (RestClientException ex) {
				throw new CivuceUnavailableException("CIVUCE auth falló: " + ex.getMessage(), ex);
			}
		}
	}

	private void invalidateToken() {
		cachedToken = null;
		tokenFetchedAt = null;
	}

	private static boolean isUnauthorized(RestClientException ex) {
		return ex.getMessage() != null && ex.getMessage().contains("401");
	}

	private static boolean isRetryable(RestClientException ex) {
		String m = ex.getMessage();
		return m != null && (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("429"));
	}

	private static String shortMsg(Throwable ex) {
		String m = ex.getMessage();
		if (m == null) {
			return ex.getClass().getSimpleName();
		}
		int html = m.indexOf("<!DOCTYPE");
		if (html > 0) {
			return m.substring(0, html).trim();
		}
		return m.length() > 180 ? m.substring(0, 180) : m;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record AuthResponse(String data) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CivuceListResponse(List<CivuceItem> data, int total) {
	}
}
