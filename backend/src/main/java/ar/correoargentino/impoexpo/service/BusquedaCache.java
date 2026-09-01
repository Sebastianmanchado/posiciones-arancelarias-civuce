package ar.correoargentino.impoexpo.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class BusquedaCache {

	private final Map<String, Object> store = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	public <T> Optional<T> get(String key) {
		return Optional.ofNullable((T) store.get(key));
	}

	public void put(String key, Object value) {
		store.put(key, value);
	}

	public void invalidate() {
		store.clear();
	}
}
