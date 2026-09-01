package ar.correoargentino.impoexpo.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SeleccionRequest(
		@NotBlank String consulta,
		@NotBlank String codigoSim) {
}
