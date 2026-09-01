package ar.correoargentino.impoexpo.web;

import ar.correoargentino.impoexpo.api.dto.ErrorResponse;
import ar.correoargentino.impoexpo.service.CivuceUnavailableException;
import ar.correoargentino.impoexpo.service.OpenRouterException;
import jakarta.validation.ConstraintViolationException;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> status(ResponseStatusException ex) {
		HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
		String codigo = status == HttpStatus.UNPROCESSABLE_ENTITY ? "SIN_RESULTADOS" : "VALIDACION";
		return json(status, codigo, ex.getReason() == null ? status.getReasonPhrase() : ex.getReason());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
		String msg = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(err -> err.getField() + ": " + err.getDefaultMessage())
				.orElse("Solicitud inválida");
		return json(HttpStatus.BAD_REQUEST, "VALIDACION", msg);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> constraint(ConstraintViolationException ex) {
		return json(HttpStatus.BAD_REQUEST, "VALIDACION", ex.getMessage());
	}

	@ExceptionHandler(OpenRouterException.class)
	public ResponseEntity<ErrorResponse> openRouter(OpenRouterException ex) {
		log.warn("OpenRouter: {}", ex.getMessage());
		return json(HttpStatus.BAD_GATEWAY, "DEPENDENCIA", ex.getMessage());
	}

	@ExceptionHandler(CivuceUnavailableException.class)
	public ResponseEntity<ErrorResponse> civuce(CivuceUnavailableException ex) {
		log.warn("CIVUCE: {}", ex.getMessage());
		return json(HttpStatus.BAD_GATEWAY, "DEPENDENCIA", ex.getMessage());
	}

	@ExceptionHandler(CompletionException.class)
	public ResponseEntity<ErrorResponse> completion(CompletionException ex) {
		Throwable cause = ex.getCause() == null ? ex : ex.getCause();
		if (cause instanceof CivuceUnavailableException civuce) {
			return civuce(civuce);
		}
		if (cause instanceof OpenRouterException or) {
			return openRouter(or);
		}
		return generic(ex);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> generic(Exception ex) {
		log.error("Error no controlado", ex);
		return json(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNO", "Ocurrió un error interno. Reintentá en unos minutos.");
	}

	private static ResponseEntity<ErrorResponse> json(HttpStatus status, String codigo, String mensaje) {
		String requestId = MDC.get(RequestIdFilter.MDC_KEY);
		return ResponseEntity.status(status)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
				.body(new ErrorResponse(mensaje, codigo, requestId));
	}
}
