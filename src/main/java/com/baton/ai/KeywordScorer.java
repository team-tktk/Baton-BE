package com.baton.ai;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Korean-friendly lexical score used alongside embeddings. */
final class KeywordScorer {
	private KeywordScorer() { }

	static double score(String query, String text) {
		String normalizedQuery = normalize(query);
		String normalizedText = normalize(text);
		if (normalizedQuery.isBlank() || normalizedText.isBlank()) return 0;

		Set<String> terms = terms(normalizedQuery);
		double termScore = terms.stream().filter(normalizedText::contains).count()
				/ (double) Math.max(1, terms.size());
		Set<String> queryBigrams = ngrams(normalizedQuery.replace(" ", ""), 2);
		Set<String> textBigrams = ngrams(normalizedText.replace(" ", ""), 2);
		long shared = queryBigrams.stream().filter(textBigrams::contains).count();
		double bigramScore = shared / (double) Math.max(1, queryBigrams.size());
		return termScore * 0.75 + bigramScore * 0.25;
	}

	private static Set<String> terms(String value) {
		Set<String> result = new HashSet<>();
		for (String token : value.split("\\s+")) {
			if (token.length() >= 2) result.add(token);
			if (token.length() >= 3 && "은는이가을를에의와과도만로으로에서에게부터까지".contains(token.substring(token.length() - 1))) {
				result.add(token.substring(0, token.length() - 1));
			}
		}
		return result;
	}

	private static Set<String> ngrams(String value, int size) {
		Set<String> result = new HashSet<>();
		for (int i = 0; i + size <= value.length(); i++) result.add(value.substring(i, i + size));
		return result;
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
	}
}
