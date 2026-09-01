package ar.correoargentino.impoexpo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.correoargentino.impoexpo.client.CivuceClient;
import ar.correoargentino.impoexpo.client.CivuceClient.CivuceItem;
import ar.correoargentino.impoexpo.client.CivuceClient.TextoPartida;
import ar.correoargentino.impoexpo.util.Embeddings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PosicionScorerTest {

	private PosicionScorer scorer;

	@BeforeEach
	void setUp() {
		scorer = new PosicionScorer();
	}

	@Test
	void ncmBase_formateaPartida() {
		assertEquals("61.09", scorer.ncmBase("6109.10.00.110Y"));
	}

	@Test
	void puntuar_bonusRemeraPartida6109() {
		CivuceItem item = new CivuceItem(
				"6109.10.00.110Y",
				"T-shirts",
				"07",
				null,
				null,
				null,
				new TextoPartida("6109.10.00.110Y", "T-SHIRTS Y CAMISETAS DE PUNTO"),
				null,
				null);
		PosicionScorer.ScoreResult score = scorer.puntuar(item, List.of("remera"), List.of("camiseta"), "remera");
		assertTrue(score.rawScore() >= 8);
	}

	@Test
	void puntuar_termoPalabraCompletaSuperaSubstringTermoplastico() {
		CivuceItem termo = new CivuceItem(
				"9617.00.00.100A",
				"Termos",
				"07",
				null,
				null,
				null,
				new TextoPartida("9617.00.00.100A", "TERMOS Y RECIPIENTES ISOTERMICOS CON TERMO"),
				null,
				null);
		CivuceItem termoplastico = new CivuceItem(
				"3901.10.00.100A",
				"Polimeros",
				"01",
				null,
				null,
				null,
				new TextoPartida("3901.10.00.100A", "POLIMEROS TERMOPLASTICOS DE ETILENO"),
				null,
				null);
		PosicionScorer.ScoreResult scoreTermo = scorer.puntuar(termo, List.of("termo"), List.of(), "termo");
		PosicionScorer.ScoreResult scoreTermoplastico = scorer.puntuar(termoplastico, List.of("termo"), List.of(), "termo");
		assertTrue(scoreTermo.matchPalabraCompleta());
		assertFalse(scoreTermoplastico.matchPalabraCompleta());
		assertTrue(scoreTermo.rawScore() > scoreTermoplastico.rawScore());
	}

	@Test
	void puntuar_usaDescripcionCompletaDeCivuceNoHojaVacia() {
		CivuceItem frasco = new CivuceItem(
				"9617.00.10.100H",
				"Con una capacidad inferior o igual a 1 l",
				"07",
				null,
				null,
				null,
				null,
				"96170010",
				List.of(
						new CivuceClient.NivelJerarquia("96", "MANUFACTURAS DIVERSAS"),
						new CivuceClient.NivelJerarquia("96.17",
								"TERMOS Y DEMAS RECIPIENTES ISOTERMICOS, MONTADOS Y AISLADOS POR VACIO"),
						new CivuceClient.NivelJerarquia("9617.00.10", "Termos y demas recipientes isotermicos"),
						new CivuceClient.NivelJerarquia("9617.00.10.100H",
								"Con una capacidad inferior o igual a 1 l")));
		PosicionScorer.ScoreResult score = scorer.puntuar(frasco, List.of("termo"), List.of("termos"), "termos");
		assertTrue(score.matchPalabraCompleta());
		assertTrue(score.rawScore() >= 12);
		assertTrue(scorer.headingDesdeJerarquia(frasco).toLowerCase().contains("termos"));
	}

	@Test
	void procesar_jerarquiaCivuceMuestraProductoNoSoloPartes() {
		CivuceItem frasco = new CivuceItem(
				"9617.00.10.100H",
				"Con una capacidad inferior o igual a 1 l",
				"01",
				null,
				null,
				null,
				null,
				"96170010",
				List.of(
						new CivuceClient.NivelJerarquia("96.17",
								"TERMOS Y DEMAS RECIPIENTES ISOTERMICOS, MONTADOS Y AISLADOS POR VACIO"),
						new CivuceClient.NivelJerarquia("9617.00.10", "Termos y demas recipientes isotermicos"),
						new CivuceClient.NivelJerarquia("9617.00.10.100H",
								"Con una capacidad inferior o igual a 1 l")));
		CivuceItem partes = new CivuceItem(
				"9617.00.20.000M",
				"Partes",
				"01",
				null,
				null,
				null,
				new TextoPartida("9617.00.20.000M",
						"TERMOS Y DEMAS RECIPIENTES ISOTERMICOS, MONTADOS Y AISLADOS POR VACIO"),
				"961700",
				List.of(
						new CivuceClient.NivelJerarquia("96.17",
								"TERMOS Y DEMAS RECIPIENTES ISOTERMICOS, MONTADOS Y AISLADOS POR VACIO"),
						new CivuceClient.NivelJerarquia("9617.00.20", "Partes")));
		List<PosicionScorer.ScoredItem> scored = List.of(frasco, partes).stream()
				.map(it -> {
					var r = scorer.puntuar(it, List.of("termo"), List.of("termos"), "termos");
					return new PosicionScorer.ScoredItem(
							it, "termos", r.rawScore(), r.matchPalabraCompleta(), true, false);
				})
				.toList();
		List<PosicionScorer.MappedPosicion> out = scorer.procesar(scored, 12, true);
		assertTrue(out.stream().anyMatch(p -> "9617.00.10".equals(p.codigoNcm())));
		assertTrue(out.stream()
				.filter(p -> "9617.00.10".equals(p.codigoNcm()))
				.anyMatch(p -> p.descripcion().toUpperCase().contains("TERMOS")));
	}

	@Test
	void procesar_partidaMatchExpandeTodasLasHojasDeCapacidad() {
		CivuceItem l1 = hojaTermo("9617.00.10.100H", "Con una capacidad inferior o igual a 1 l");
		CivuceItem l2 = hojaTermo("9617.00.10.200N", "Con una capacidad superior a 1 l pero inferior o igual a 2,5 l");
		CivuceItem l3 = hojaTermo("9617.00.10.300U", "Con una capacidad superior a 2,5 l");
		List<PosicionScorer.ScoredItem> scored = List.of(l1, l2, l3).stream()
				.map(it -> {
					var r = scorer.puntuar(it, List.of("termo"), List.of("termos"), "termos");
					return new PosicionScorer.ScoredItem(
							it, "termos", r.rawScore(), r.matchPalabraCompleta(), true, false);
				})
				.toList();
		List<PosicionScorer.MappedPosicion> out = scorer.procesar(scored, 12, true);
		assertTrue(out.stream().anyMatch(p -> p.codigoSim().endsWith("100H")));
		assertTrue(out.stream().anyMatch(p -> p.codigoSim().contains("200N")),
				"un termo de 2 l es 9617.00.10.200N y debe listarse");
		assertTrue(out.stream().anyMatch(p -> p.codigoSim().endsWith("300U")));
	}

	@Test
	void esAncestroNcm_filtraHojasDeOtraCapacidad() {
		assertTrue(PosicionesArancelariasService.esAncestroNcm("9617.00.10", "9617.00.10.200N"));
		assertFalse(PosicionesArancelariasService.esAncestroNcm("9617.00.10.1", "9617.00.10.200N"));
	}

	private static CivuceItem hojaTermo(String codigo, String hoja) {
		return new CivuceItem(
				codigo,
				hoja,
				"01",
				null,
				null,
				null,
				null,
				"96170010",
				List.of(
						new CivuceClient.NivelJerarquia("96.17",
								"TERMOS Y DEMAS RECIPIENTES ISOTERMICOS, MONTADOS Y AISLADOS POR VACIO"),
						new CivuceClient.NivelJerarquia("9617.00.10", "Termos y demas recipientes isotermicos"),
						new CivuceClient.NivelJerarquia(codigo, hoja)));
	}

	@Test
	void textoEncabezadoParaEmbedding_usaPartidaNoHojaDeCapacidad() {
		CivuceItem frasco = hojaTermo("9617.00.10.200N",
				"Con una capacidad superior a 1 l pero inferior o igual a 2,5 l");
		String texto = scorer.textoEncabezadoParaEmbedding(frasco);
		assertTrue(texto.toLowerCase().contains("termos"));
		assertFalse(texto.toLowerCase().contains("2,5"));
	}

	@Test
	void puntosEmbedding_ignoraCosenoDebilYNoSuperaMatchLexico() {
		assertEquals(0, scorer.puntosEmbedding(0.20));
		assertEquals(0, scorer.puntosEmbedding(0.39));
		assertTrue(scorer.puntosEmbedding(0.85) > 0);
		assertTrue(scorer.puntosEmbedding(0.85) <= 14);
	}

	@Test
	void aplicarEmbeddingPartidas_mismaPartidaSumaIgualATodasLasHojas() {
		CivuceItem l1 = hojaTermo("9617.00.10.100H", "Con una capacidad inferior o igual a 1 l");
		CivuceItem l2 = hojaTermo("9617.00.10.200N", "Con una capacidad superior a 1 l pero inferior o igual a 2,5 l");
		List<PosicionScorer.ScoredItem> scored = List.of(l1, l2).stream()
				.map(it -> new PosicionScorer.ScoredItem(it, "termo", 20, true, true, false))
				.toList();
		List<PosicionScorer.ScoredItem> out = scorer.aplicarEmbeddingPartidas(
				scored, Map.of("9617.00", 0.9));
		assertEquals(out.get(0).rawScore(), out.get(1).rawScore());
		assertTrue(out.get(0).rawScore() > 20);
	}

	@Test
	void sinonimosLexico_incluyeTermosParaTermo() {
		SinonimosLexico lexico = new SinonimosLexico();
		List<String> terminos = lexico.terminosDeBusqueda("termo");
		assertTrue(terminos.contains("termo"));
		assertTrue(terminos.contains("termos"));
		assertTrue(terminos.contains("recipientes isotermicos"));
	}

	@Test
	void sinonimosLexico_incluyeCamisetaParaRemera() {
		SinonimosLexico lexico = new SinonimosLexico();
		List<String> terminos = lexico.terminosDeBusqueda("remera");
		assertTrue(terminos.contains("remera"));
		assertTrue(terminos.contains("camiseta"));
	}

	@Test
	void sinonimosLexico_filtraGenericos() {
		assertTrue(SinonimosLexico.esTerminoGenerico("algodon"));
		assertFalse(SinonimosLexico.esTerminoGenerico("remera"));
	}

	@Test
	void unidadMedida_resuelveCodigo07() throws Exception {
		UnidadMedidaCatalogo catalogo = new UnidadMedidaCatalogo(new ObjectMapper());
		catalogo.load();
		assertEquals("UNIDAD", catalogo.resolverNombre("07"));
		assertEquals("KILOGRAMO", catalogo.resolverNombre("01"));
	}

	@Test
	void cosine_vectoresIgualesEsUno() {
		double[] v = {1, 0, 0};
		assertEquals(1.0, Embeddings.cosine(v, v), 1e-9);
	}
}
