package ar.correoargentino.impoexpo.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

public final class LlmJson {

	private LlmJson() {
	}

	public static String extractJsonObject(String content) {
		if (content == null) {
			return "{}";
		}
		String t = content.trim();
		if (t.startsWith("```")) {
			t = t.replaceFirst("^```(?:json)?\\s*", "");
			int fence = t.lastIndexOf("```");
			if (fence >= 0) {
				t = t.substring(0, fence);
			}
			t = t.trim();
		}
		int start = t.indexOf('{');
		int end = t.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return t.substring(start, end + 1);
		}
		return t;
	}

	public static Map<String, Object> parseObject(String content, ObjectMapper mapper) {
		try {
			return mapper.readValue(extractJsonObject(content), new TypeReference<>() {
			});
		} catch (Exception ex) {
			return Map.of();
		}
	}

	public static List<String> stringList(Map<String, Object> parsed, String key) {
		Object arr = parsed.get(key);
		if (!(arr instanceof List<?> list)) {
			return List.of();
		}
		return list.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.map(TextNormalizer::normalizar)
				.filter(s -> s.length() >= 3)
				.distinct()
				.toList();
	}
}
