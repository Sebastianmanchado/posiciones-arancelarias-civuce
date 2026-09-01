package ar.correoargentino.impoexpo.service;

import java.util.List;

public record InterpretacionBusqueda(
		String productoInterpretado,
		List<String> terminosBusqueda,
		List<String> tokensRelevancia) {

	public static InterpretacionBusqueda vacia() {
		return new InterpretacionBusqueda("", List.of(), List.of());
	}

	public boolean tieneTerminos() {
		return terminosBusqueda != null && !terminosBusqueda.isEmpty();
	}
}
