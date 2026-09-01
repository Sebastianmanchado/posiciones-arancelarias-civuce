package ar.correoargentino.impoexpo.client;

import ar.correoargentino.impoexpo.config.AppProperties;
import ar.correoargentino.impoexpo.service.InterpretacionBusqueda;
import ar.correoargentino.impoexpo.service.OpenRouterException;
import ar.correoargentino.impoexpo.service.SinonimosLexico;
import ar.correoargentino.impoexpo.util.Embeddings;
import ar.correoargentino.impoexpo.util.LlmJson;
import ar.correoargentino.impoexpo.util.TextNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenRouterClient {

	private static final Logger log = LoggerFactory.getLogger(OpenRouterClient.class);

	private static final String SYSTEM_PROMPT = """
			Sos un asistente de clasificación arancelaria argentina (CIVUCE / NCM).
			Interpretá qué producto busca el usuario y generá términos para consultar el nomenclador.
			Reglas:
			- terminosBusqueda: frases o palabras clave del MISMO producto (máx 4) que existan en el nomenclador CIVUCE. Preferí plural y términos técnicos del NCM (ej. "termo" → "termos", "recipientes isotermicos"; "notebook" → "procesamiento de datos", "computadoras"; "remera" → "camisetas", "t-shirts"). NO uses frases que CIVUCE no indexa ("termo vacio", "computadora portatil").
			- tokensRelevancia: tokens para rankear resultados (singular o plural, máx 6).
			- NO uses materiales genéricos sueltos (algodón, plástico, metal).
			- NO confundas productos distintos que comparten substring (termo ≠ termoplastico).
			JSON: {
			  "productoInterpretado": string,
			  "terminosBusqueda": string[],
			  "tokensRelevancia": string[]
			}""";

	private final RestClient restClient;
	private final AppProperties properties;
	private final ObjectMapper objectMapper;

	public OpenRouterClient(
			@Qualifier("openRouterRestClient") RestClient restClient,
			AppProperties properties,
			ObjectMapper objectMapper) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	public boolean configured() {
		return properties.getOpenrouter().isConfigured();
	}

	public InterpretacionBusqueda interpretar(String producto) {
		if (!configured()) {
			return InterpretacionBusqueda.vacia();
		}
		Map<String, Object> body = Map.of(
				"model", properties.getOpenrouter().getModel(),
				"temperature", 0.1,
				"max_tokens", 220,
				"response_format", Map.of("type", "json_object"),
				"messages", List.of(
						Map.of("role", "system", "content", SYSTEM_PROMPT),
						Map.of("role", "user", "content", objectMapper.valueToTree(Map.of("producto", producto)).toString())));
		try {
			ChatCompletion response = restClient.post()
					.uri("/chat/completions")
					.body(body)
					.retrieve()
					.body(ChatCompletion.class);
			if (response == null || response.choices() == null || response.choices().isEmpty()
					|| response.choices().get(0).message() == null
					|| response.choices().get(0).message().content() == null) {
				log.warn("OpenRouter interpretación: respuesta vacía");
				return InterpretacionBusqueda.vacia();
			}
			return parseInterpretacion(response.choices().get(0).message().content(), producto);
		} catch (RestClientException ex) {
			log.warn("OpenRouter interpretación falló: {}", ex.getMessage());
			throw new OpenRouterException("No se pudo interpretar la búsqueda con OpenRouter: " + ex.getMessage(), ex);
		}
	}

	public double[] embed(String texto) {
		if (!configured() || texto == null || texto.isBlank()) {
			return new double[0];
		}
		List<double[]> batch = embedAll(List.of(texto));
		return batch.isEmpty() ? new double[0] : batch.get(0);
	}

	/** Un llamado batch. Si falla, vectores vacíos (el ranking léxico sigue valiendo). */
	public List<double[]> embedAll(List<String> textos) {
		if (!configured() || textos == null || textos.isEmpty()) {
			return textos == null ? List.of() : textos.stream().map(t -> new double[0]).toList();
		}
		Map<String, Object> body = Map.of(
				"model", properties.getOpenrouter().getEmbeddingModel(),
				"input", textos);
		try {
			EmbeddingResponse response = restClient.post()
					.uri("/embeddings")
					.body(body)
					.retrieve()
					.body(EmbeddingResponse.class);
			double[][] byIndex = new double[textos.size()][];
			Arrays.fill(byIndex, new double[0]);
			if (response == null || response.data() == null) {
				return Arrays.asList(byIndex);
			}
			int sequential = 0;
			for (EmbeddingData d : response.data()) {
				int idx = d.index() != null ? d.index() : sequential;
				sequential++;
				if (idx < 0 || idx >= byIndex.length || d.embedding() == null) {
					continue;
				}
				byIndex[idx] = Embeddings.toArray(d.embedding());
			}
			return Arrays.asList(byIndex);
		} catch (RestClientException ex) {
			log.warn("OpenRouter embeddings batch falló: {}", ex.getMessage());
			return textos.stream().map(t -> new double[0]).toList();
		}
	}

	private InterpretacionBusqueda parseInterpretacion(String content, String productoOriginal) {
		Map<String, Object> parsed = LlmJson.parseObject(content, objectMapper);
		String interpretado = stringField(parsed, "productoInterpretado");
		List<String> terminos = filtrarTerminos(LlmJson.stringList(parsed, "terminosBusqueda"), productoOriginal);
		List<String> tokens = filtrarTokens(LlmJson.stringList(parsed, "tokensRelevancia"));
		return new InterpretacionBusqueda(interpretado, terminos, tokens);
	}

	private List<String> filtrarTerminos(List<String> raw, String productoOriginal) {
		String orig = TextNormalizer.normalizar(productoOriginal);
		List<String> out = new ArrayList<>();
		for (String t : raw) {
			String v = TextNormalizer.normalizar(t);
			if (v.length() < 3 || SinonimosLexico.esTerminoGenerico(v) || out.contains(v)) {
				continue;
			}
			out.add(v);
			if (out.size() >= 4) {
				break;
			}
		}
		if (orig.length() >= 3 && !out.contains(orig)) {
			out.add(0, orig);
		}
		return out.size() > 4 ? out.subList(0, 4) : out;
	}

	private List<String> filtrarTokens(List<String> raw) {
		List<String> out = new ArrayList<>();
		for (String t : raw) {
			String v = TextNormalizer.normalizar(t);
			if (v.length() < 3 || SinonimosLexico.esTerminoGenerico(v) || out.contains(v)) {
				continue;
			}
			out.add(v);
			if (out.size() >= 6) {
				break;
			}
		}
		return out;
	}

	private static String stringField(Map<String, Object> parsed, String key) {
		Object v = parsed.get(key);
		return v == null ? "" : String.valueOf(v).trim();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record ChatCompletion(List<Choice> choices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Message(String role, String content) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingResponse(List<EmbeddingData> data) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record EmbeddingData(Integer index, List<Double> embedding) {
	}
}
