package ar.correoargentino.impoexpo.service;

import ar.correoargentino.impoexpo.util.TextNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SinonimosLexico {

	private static final Map<String, List<String>> SINONIMOS = Map.ofEntries(
			Map.entry("remera", List.of("camiseta", "t-shirt")),
			Map.entry("remeras", List.of("camiseta", "t-shirt")),
			Map.entry("playera", List.of("camiseta", "t-shirt")),
			Map.entry("jeans", List.of("pantalon de mezclilla", "vaqueros")),
			Map.entry("jean", List.of("pantalon de mezclilla", "vaqueros")),
			Map.entry("celular", List.of("telefono inteligente", "smartphone")),
			Map.entry("celu", List.of("telefono inteligente", "smartphone")),
			Map.entry("powerbank", List.of("bateria portatil", "acumulador de litio")),
			Map.entry("power bank", List.of("bateria portatil", "acumulador de litio")),
			Map.entry("notebook", List.of("procesamiento de datos", "computadoras portatiles")),
			Map.entry("laptop", List.of("procesamiento de datos", "computadoras portatiles")),
			Map.entry("perfume", List.of("agua de tocador", "fragancia")),
			Map.entry("zapatillas", List.of("calzado", "calzado deportivo")),
			Map.entry("zapatilla", List.of("calzado deportivo")),
			Map.entry("termo", List.of("termos", "recipientes isotermicos")),
			Map.entry("termos", List.of("recipientes isotermicos")),
			Map.entry("yerba", List.of("yerba mate")),
			Map.entry("yerba mate", List.of()));

	private static final Set<String> TERMINOS_GENERICOS = Set.of(
			"algodon", "lana", "cuero", "sintetico", "plastico", "metal", "madera",
			"vidrio", "papel", "tela", "textil", "natural", "artificial", "punto",
			"tejido", "color", "talle", "adulto", "nino", "nina", "hombre", "mujer",
			"envio", "exportar", "producto", "recipiente", "recipientes", "envase", "envases");

	public List<String> terminosDeBusqueda(String descripcion) {
		String n = TextNormalizer.normalizar(descripcion);
		List<String> out = new ArrayList<>();
		addTermino(out, n);
		for (String tok : tokensProducto(descripcion)) {
			addTermino(out, tok);
			for (String syn : SINONIMOS.getOrDefault(tok, List.of())) {
				addTermino(out, syn);
			}
		}
		for (Map.Entry<String, List<String>> entry : SINONIMOS.entrySet()) {
			if (n.contains(entry.getKey())) {
				for (String syn : entry.getValue()) {
					addTermino(out, syn);
				}
			}
		}
		return out.size() > 4 ? out.subList(0, 4) : out;
	}

	public List<String> tokensProducto(String descripcion) {
		List<String> tokens = new ArrayList<>();
		for (String tok : TextNormalizer.tokens(descripcion)) {
			if (tok.length() >= 4 && !TERMINOS_GENERICOS.contains(tok)) {
				tokens.add(tok);
			}
		}
		return tokens;
	}

	public static boolean esTerminoGenerico(String termino) {
		return TERMINOS_GENERICOS.contains(TextNormalizer.normalizar(termino));
	}

	private void addTermino(List<String> out, String termino) {
		String v = TextNormalizer.normalizar(termino);
		if (v.length() < 3 || TERMINOS_GENERICOS.contains(v) || out.contains(v)) {
			return;
		}
		out.add(v);
	}
}
