package ar.correoargentino.impoexpo.service;

public class CivuceUnavailableException extends RuntimeException {

	public CivuceUnavailableException(String message) {
		super(message);
	}

	public CivuceUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
