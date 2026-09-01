package ar.correoargentino.impoexpo.api.dto;

public record SeleccionResponse(
		boolean ok,
		String consulta,
		String codigoSim,
		String unidadCodigo,
		String unidadMedida,
		String requestId) {
}
