package ar.correoargentino.impoexpo.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfig {

	@Bean
	CorsFilter corsFilter(AppProperties properties) {
		List<String> origins = Arrays.stream(properties.getCors().getAllowedOrigins().split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(origins);
		config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setExposedHeaders(List.of("X-Request-Id"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return new CorsFilter(source);
	}
}
