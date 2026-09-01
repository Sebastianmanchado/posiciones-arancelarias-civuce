package ar.correoargentino.impoexpo.web;

import ar.correoargentino.impoexpo.api.dto.HealthResponse;
import ar.correoargentino.impoexpo.api.dto.HealthResponse.Dependencia;
import ar.correoargentino.impoexpo.client.CivuceClient;
import ar.correoargentino.impoexpo.client.OpenRouterClient;
import ar.correoargentino.impoexpo.config.AppProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

	private final CivuceClient civuceClient;
	private final OpenRouterClient openRouterClient;
	private final AppProperties properties;

	public HealthController(
			CivuceClient civuceClient,
			OpenRouterClient openRouterClient,
			AppProperties properties) {
		this.civuceClient = civuceClient;
		this.openRouterClient = openRouterClient;
		this.properties = properties;
	}

	@GetMapping("/health")
	public HealthResponse health() {
		CivuceClient.HealthPing civuce = civuceClient.ping();
		boolean orOk = openRouterClient.configured();
		Dependencia orDep = new Dependencia(
				orOk ? "UP" : "DOWN",
				orOk
						? "API key configurada · modelo " + properties.getOpenrouter().getModel()
						: "OPENROUTER_API_KEY no configurada");
		Dependencia civDep = new Dependencia(
				civuce.reachable() ? "UP" : "DOWN",
				civuce.detalle());
		boolean up = civuce.reachable();
		Map<String, Dependencia> deps = new LinkedHashMap<>();
		deps.put("openrouter", orDep);
		deps.put("civuce", civDep);
		return new HealthResponse(up ? "UP" : "DEGRADED", deps);
	}
}
