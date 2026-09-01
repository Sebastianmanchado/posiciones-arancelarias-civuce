package ar.correoargentino.impoexpo.service;

import ar.correoargentino.impoexpo.config.AppProperties;
import ar.correoargentino.impoexpo.util.Embeddings;
import ar.correoargentino.impoexpo.util.TextNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HistorialSelecciones {

	private static final Logger log = LoggerFactory.getLogger(HistorialSelecciones.class);

	private final AppProperties properties;
	private final ObjectMapper objectMapper;
	private final List<Seleccion> entries = new ArrayList<>();
	private Path file;

	public HistorialSelecciones(AppProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Seleccion(
			String consulta,
			String consultaNorm,
			String codigoSim,
			String unidad,
			List<Double> embedding,
			String ts) {
	}

	public record Match(String codigoSim, String unidad, double cosine, int frecuencia, String consultaOrigen) {
	}

	@PostConstruct
	public void load() {
		file = Path.of(properties.getHistorial().getPath());
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			if (!Files.exists(file)) {
				Files.createFile(file);
				return;
			}
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				try {
					entries.add(objectMapper.readValue(line, Seleccion.class));
				} catch (Exception ex) {
					log.warn("Línea de historial ilegible: {}", ex.getMessage());
				}
			}
		} catch (IOException ex) {
			log.warn("No se pudo cargar historial {}: {}", file, ex.getMessage());
		}
	}

	public synchronized void registrar(String consulta, String codigoSim, String unidad, double[] embedding) {
		List<Double> vec = new ArrayList<>();
		if (embedding != null) {
			for (double v : embedding) {
				vec.add(v);
			}
		}
		Seleccion row = new Seleccion(
				consulta,
				TextNormalizer.normalizar(consulta),
				codigoSim,
				unidad == null ? "" : unidad,
				vec,
				Instant.now().toString());
		entries.add(row);
		try {
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			String json = objectMapper.writeValueAsString(row);
			Files.writeString(file, json + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ex) {
			log.warn("No se pudo persistir selección: {}", ex.getMessage());
		}
	}

	public List<Match> similares(String consulta, double[] queryEmbedding) {
		double umbral = properties.getHistorial().getUmbral();
		String norm = TextNormalizer.normalizar(consulta);
		Map<String, Acc> byCodigo = new LinkedHashMap<>();
		synchronized (this) {
			for (Seleccion s : entries) {
				double sim;
				if (queryEmbedding != null && queryEmbedding.length > 0
						&& s.embedding() != null && !s.embedding().isEmpty()) {
					sim = Embeddings.cosine(queryEmbedding, Embeddings.toArray(s.embedding()));
				} else if (norm.equals(s.consultaNorm())) {
					sim = 1.0;
				} else {
					continue;
				}
				if (sim < umbral) {
					continue;
				}
				Acc acc = byCodigo.computeIfAbsent(s.codigoSim(), k -> new Acc(s.codigoSim(), s.unidad(), s.consulta()));
				acc.frecuencia++;
				acc.cosine = Math.max(acc.cosine, sim);
			}
		}
		return byCodigo.values().stream()
				.map(a -> new Match(a.codigo, a.unidad, a.cosine, a.frecuencia, a.consultaOrigen))
				.sorted(Comparator.comparingDouble(Match::cosine).reversed())
				.toList();
	}

	private static final class Acc {
		final String codigo;
		final String unidad;
		final String consultaOrigen;
		int frecuencia;
		double cosine;

		Acc(String codigo, String unidad, String consultaOrigen) {
			this.codigo = codigo;
			this.unidad = unidad;
			this.consultaOrigen = consultaOrigen;
		}
	}
}
