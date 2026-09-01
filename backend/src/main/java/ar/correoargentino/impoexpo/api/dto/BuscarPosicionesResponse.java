package ar.correoargentino.impoexpo.api.dto;

import java.util.List;

public record BuscarPosicionesResponse(
		String consulta,
		String fase,
		List<String> terminosUsados,
		boolean fuenteLlm,
		boolean boostHistorial,
		List<GrupoPosiciones> resultados,
		String avisoLegal,
		String requestId,
		long duracionMs) {

	public record GrupoPosiciones(String grupo, List<ItemPosicion> items) {
	}

	public record ItemPosicion(
			String codigoNcm,
			String codigoSim,
			String descripcion,
			double confianza,
			String unidadCodigo,
			String unidadMedida,
			boolean sugeridoPorIa,
			boolean boostHistorial) {
	}
}
