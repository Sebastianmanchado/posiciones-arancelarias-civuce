package ar.correoargentino.impoexpo.api.dto;

import java.util.List;

public record DetallePosicionResponse(
		String codigoNcm,
		String codigoSim,
		String descripcion,
		String unidadCodigo,
		String unidadMedida,
		String derechosExportacion,
		List<NivelJerarquia> jerarquia,
		String avisoLegal,
		String requestId) {

	public record NivelJerarquia(String posicion, String descripcion) {
	}
}
