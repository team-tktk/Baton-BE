package com.baton.ai;

import java.util.ArrayList;
import java.util.List;

import com.baton.ai.dto.EvidenceHighlight;

/** Exact matching apart from whitespace. Ambiguous quotes deliberately get no guessed page. */
public final class EvidenceLocator {
	private EvidenceLocator() { }

	public static Range find(String text, String quote) {
		if (text == null || quote == null || quote.isBlank()) return null;
		Normalized haystack = normalize(text);
		String needle = normalize(quote).text();
		if (needle.isEmpty()) return null;
		int start = haystack.text().indexOf(needle);
		if (start < 0 || haystack.text().indexOf(needle, start + 1) >= 0) return null;
		return new Range(haystack.offsets().get(start), haystack.offsets().get(start + needle.length() - 1) + 1);
	}

	public static Located locate(String text, String quote, List<PdfTextLocation> locations) {
		Range range = find(text, quote);
		if (range == null) return new Located(null, null, List.of());
		return at(text, range, locations);
	}

	public static Located at(String text, Range range, List<PdfTextLocation> locations) {
		List<PdfTextLocation> matching = locations == null ? List.of() : locations.stream()
				.filter(p -> p.start() < range.end() && range.start() < p.end()).toList();
		return new Located(matching.isEmpty() ? null : matching.getFirst().page(),
				text.substring(range.start(), range.end()), matching.stream().map(PdfTextLocation::highlight)
				.filter(java.util.Objects::nonNull).distinct().toList());
	}

	private static Normalized normalize(String text) {
		StringBuilder value = new StringBuilder();
		List<Integer> offsets = new ArrayList<>();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
				value.append(c); offsets.add(i);
			}
		}
		return new Normalized(value.toString(), offsets);
	}

	public record Range(int start, int end) { }
	public record Located(Integer page, String quote, List<EvidenceHighlight> highlights) { }
	private record Normalized(String text, List<Integer> offsets) { }
}
