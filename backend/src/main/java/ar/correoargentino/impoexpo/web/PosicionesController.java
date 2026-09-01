package ar.correoargentino.impoexpo.web;

import ar.correoargentino.impoexpo.api.dto.BuscarPosicionesResponse;
import ar.correoargentino.impoexpo.api.dto.DetallePosicionResponse;
import ar.correoargentino.impoexpo.api.dto.SeleccionRequest;
import ar.correoargentino.impoexpo.api.dto.SeleccionResponse;
import ar.correoargentino.impoexpo.service.PosicionesArancelariasService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/posiciones")
public class PosicionesController {

	private final PosicionesArancelariasService service;

	public PosicionesController(PosicionesArancelariasService service) {
		this.service = service;
	}

	@GetMapping("/buscar")
	public BuscarPosicionesResponse buscar(
			@RequestParam("q") String q,
			@RequestParam(value = "limite", required = false, defaultValue = "0") int limite) {
		return service.buscar(q, limite);
	}

	@GetMapping("/{codigo}")
	public DetallePosicionResponse detalle(@PathVariable String codigo) {
		return service.detalle(codigo);
	}

	@PostMapping("/seleccion")
	public SeleccionResponse seleccion(@Valid @RequestBody SeleccionRequest body) {
		return service.registrarSeleccion(body.consulta(), body.codigoSim());
	}
}
