package ar.correoargentino.impoexpo.util;

public final class Embeddings {

	private Embeddings() {
	}

	public static double cosine(double[] a, double[] b) {
		if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
			return 0;
		}
		double dot = 0;
		double na = 0;
		double nb = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			na += a[i] * a[i];
			nb += b[i] * b[i];
		}
		if (na == 0 || nb == 0) {
			return 0;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}

	public static double[] toArray(java.util.List<Double> list) {
		if (list == null || list.isEmpty()) {
			return new double[0];
		}
		double[] out = new double[list.size()];
		for (int i = 0; i < list.size(); i++) {
			out[i] = list.get(i) == null ? 0 : list.get(i);
		}
		return out;
	}
}
