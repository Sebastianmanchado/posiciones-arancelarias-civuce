package ar.correoargentino.impoexpo;

import ar.correoargentino.impoexpo.config.AppProperties;
import ar.correoargentino.impoexpo.util.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ImpoExpoHandoffApplication {

	public static void main(String[] args) {
		DotEnvLoader.load();
		SpringApplication.run(ImpoExpoHandoffApplication.class, args);
	}
}
