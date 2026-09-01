package ar.correoargentino.impoexpo.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class DotEnvLoader {

	private DotEnvLoader() {
	}

	public static void load() {
		for (Path candidate : List.of(Path.of(".env"), Path.of("../.env"), Path.of("backend/.env"))) {
			if (Files.isRegularFile(candidate)) {
				loadFile(candidate);
			}
		}
	}

	private static void loadFile(Path path) {
		try {
			for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
				String line = raw.trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				int eq = line.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = line.substring(0, eq).trim();
				String value = unquote(line.substring(eq + 1).trim());
				if (System.getenv(key) == null && System.getProperty(key) == null) {
					System.setProperty(key, value);
				}
			}
		} catch (IOException ignored) {
			// El arranque no debe fallar por un .env ilegible.
		}
	}

	private static String unquote(String value) {
		if (value.length() >= 2) {
			char first = value.charAt(0);
			char last = value.charAt(value.length() - 1);
			if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
				return value.substring(1, value.length() - 1);
			}
		}
		return value;
	}
}
