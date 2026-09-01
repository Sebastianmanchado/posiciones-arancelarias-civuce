package ar.correoargentino.impoexpo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class UnidadMedidaCatalogo {

	private final ObjectMapper objectMapper;
	private Map<String, String> porCodigo = Map.of();

	public UnidadMedidaCatalogo(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	public void load() throws Exception {
		ClassPathResource resource = new ClassPathResource("catalogo/unidades-medida.json");
		try (InputStream in = resource.getInputStream()) {
			porCodigo = objectMapper.readValue(in, new TypeReference<>() {
			});
		}
	}

	public String resolverNombre(String codigo) {
		if (codigo == null || codigo.isBlank()) {
			return "";
		}
		String key = codigo.trim();
		if (key.length() == 1) {
			key = "0" + key;
		}
		return porCodigo.getOrDefault(key, key);
	}
}
