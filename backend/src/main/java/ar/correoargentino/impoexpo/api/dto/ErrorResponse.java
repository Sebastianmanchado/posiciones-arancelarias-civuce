package ar.correoargentino.impoexpo.api.dto;

public record ErrorResponse(String mensaje, String codigo, String requestId) {
}
