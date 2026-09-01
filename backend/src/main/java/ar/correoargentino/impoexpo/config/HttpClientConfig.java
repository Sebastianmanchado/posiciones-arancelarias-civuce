package ar.correoargentino.impoexpo.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClientConfig {

	@Bean
	RestClient openRouterRestClient(AppProperties properties) {
		AppProperties.OpenRouter cfg = properties.getOpenrouter();
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(cfg.getTimeoutSeconds()));
		return RestClient.builder()
				.baseUrl(trimSlash(cfg.getBaseUrl()))
				.requestFactory(factory)
				.defaultHeader("Authorization", "Bearer " + (cfg.getApiKey() == null ? "" : cfg.getApiKey()))
				.defaultHeader("HTTP-Referer", "https://www.correoargentino.com.ar")
				.defaultHeader("X-Title", "Correo Argentino ImpoExpo Handoff")
				.defaultHeader("Content-Type", "application/json")
				.build();
	}

	@Bean
	RestClient civuceRestClient(AppProperties properties) {
		AppProperties.Civuce cfg = properties.getCivuce();
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(10));
		factory.setReadTimeout(Duration.ofSeconds(cfg.getTimeoutSeconds()));
		return RestClient.builder()
				.baseUrl(trimSlash(cfg.getBaseUrl()))
				.requestFactory(factory)
				.build();
	}

	@Bean(destroyMethod = "shutdown")
	ExecutorService civuceSearchExecutor() {
		return Executors.newFixedThreadPool(8, r -> {
			Thread t = new Thread(r, "civuce-search");
			t.setDaemon(true);
			return t;
		});
	}

	private static String trimSlash(String url) {
		if (url == null) {
			return "";
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
