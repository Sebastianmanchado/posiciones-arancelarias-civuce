package ar.correoargentino.impoexpo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

	private String avisoLegal = "";
	private final Cors cors = new Cors();
	private final OpenRouter openrouter = new OpenRouter();
	private final Civuce civuce = new Civuce();
	private final Historial historial = new Historial();

	public String getAvisoLegal() {
		return avisoLegal;
	}

	public void setAvisoLegal(String avisoLegal) {
		this.avisoLegal = avisoLegal == null ? "" : avisoLegal.trim().replaceAll("\\s+", " ");
	}

	public Cors getCors() {
		return cors;
	}

	public OpenRouter getOpenrouter() {
		return openrouter;
	}

	public Civuce getCivuce() {
		return civuce;
	}

	public Historial getHistorial() {
		return historial;
	}

	public static class Cors {
		private String allowedOrigins = "http://localhost:8080";

		public String getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(String allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}
	}

	public static class OpenRouter {
		private String apiKey = "";
		private String baseUrl = "https://openrouter.ai/api/v1";
		private String model = "google/gemini-2.5-flash-lite";
		private String embeddingModel = "openai/text-embedding-3-small";
		private int timeoutSeconds = 20;

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public String getEmbeddingModel() {
			return embeddingModel;
		}

		public void setEmbeddingModel(String embeddingModel) {
			this.embeddingModel = embeddingModel;
		}

		public int getTimeoutSeconds() {
			return timeoutSeconds;
		}

		public void setTimeoutSeconds(int timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
		}

		public boolean isConfigured() {
			return apiKey != null && !apiKey.isBlank();
		}
	}

	public static class Civuce {
		private String baseUrl = "https://qa.ci.vuce.gob.ar";
		private String authEmail = "vuce@vuce.gob.ar";
		private int timeoutSeconds = 20;
		private int maxResultados = 12;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getAuthEmail() {
			return authEmail;
		}

		public void setAuthEmail(String authEmail) {
			this.authEmail = authEmail;
		}

		public int getTimeoutSeconds() {
			return timeoutSeconds;
		}

		public void setTimeoutSeconds(int timeoutSeconds) {
			this.timeoutSeconds = timeoutSeconds;
		}

		public int getMaxResultados() {
			return maxResultados;
		}

		public void setMaxResultados(int maxResultados) {
			this.maxResultados = maxResultados;
		}
	}

	public static class Historial {
		private String path = "data/selecciones.jsonl";
		private double umbral = 0.82;

		public String getPath() {
			return path;
		}

		public void setPath(String path) {
			this.path = path;
		}

		public double getUmbral() {
			return umbral;
		}

		public void setUmbral(double umbral) {
			this.umbral = umbral;
		}
	}
}
