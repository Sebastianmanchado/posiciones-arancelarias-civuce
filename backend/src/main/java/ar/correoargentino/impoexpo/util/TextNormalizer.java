package ar.correoargentino.impoexpo.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

public final class TextNormalizer {

	private TextNormalizer() {
	}

	public static String normalizar(String s) {
		if (s == null) {
			return "";
		}
		String n = Normalizer.normalize(s, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("<[^>]+>", " ")
				.replaceAll("&[a-z]+;", " ")
				.replaceAll("[^a-z0-9]+", " ")
				.replaceAll("\\s+", " ")
				.trim();
		return n;
	}

	public static String limpiarHtml(String s) {
		if (s == null) {
			return "";
		}
		return s.replaceAll("<[^>]+>", " ")
				.replaceAll("&[a-zA-Z]+;", " ")
				.replaceAll("\\s+", " ")
				.trim();
	}

	public static String[] tokens(String s) {
		String n = normalizar(s);
		if (n.isBlank()) {
			return new String[0];
		}
		return Arrays.stream(n.split(" "))
				.filter(t -> t.length() >= 3)
				.toArray(String[]::new);
	}
}
