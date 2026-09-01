package ar.correoargentino.impoexpo.util;

import java.util.LinkedHashSet;
import java.util.Set;

/** Variantes singular/plural conservadoras para matching por palabra completa. */
public final class PluralSpanish {

	private PluralSpanish() {
	}

	public static Set<String> variantes(String token) {
		Set<String> out = new LinkedHashSet<>();
		if (token == null || token.isBlank()) {
			return out;
		}
		String t = TextNormalizer.normalizar(token);
		if (t.length() < 3) {
			return out;
		}
		out.add(t);
		if (t.endsWith("es") && t.length() > 4) {
			out.add(t.substring(0, t.length() - 2));
			out.add(t.substring(0, t.length() - 1));
		}
		if (t.endsWith("s") && t.length() > 4 && !t.endsWith("us") && !t.endsWith("is")) {
			out.add(t.substring(0, t.length() - 1));
		}
		if (!t.endsWith("s")) {
			out.add(t + "s");
		}
		return out;
	}

	public static boolean coincidePalabraCompleta(Set<String> tokensItem, String queryToken) {
		if (queryToken == null || queryToken.isBlank() || tokensItem == null || tokensItem.isEmpty()) {
			return false;
		}
		Set<String> variantes = variantes(queryToken);
		for (String itemTok : tokensItem) {
			if (variantes.contains(itemTok)) {
				return true;
			}
		}
		return false;
	}
}
