package ar.correoargentino.impoexpo.api.dto;

import java.util.Map;

public record HealthResponse(String status, Map<String, Dependencia> dependencias) {

	public record Dependencia(String status, String detalle) {
	}
}
