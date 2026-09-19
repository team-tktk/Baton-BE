package com.baton.ai;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/** Split PDFs at page boundaries before token splitting; never split a cross-page masking token. */
final class EvidenceChunker {
	private EvidenceChunker() { }

	static List<Document> split(String text, List<PdfTextLocation> locations, TokenTextSplitter splitter) {
		List<Integer> boundaries = new ArrayList<>();
		boundaries.add(0);
		int page = -1;
		int previousEnd = 0;
		if (locations != null) {
			for (PdfTextLocation location : locations) {
				if (page != -1 && page != location.page() && location.start() >= previousEnd
						&& location.start() > boundaries.getLast() && location.start() < text.length()) {
					boundaries.add(location.start());
				}
				page = location.page();
				previousEnd = Math.max(previousEnd, location.end());
			}
		}
		boundaries.add(text.length());
		List<Document> chunks = new ArrayList<>();
		for (int i = 0; i < boundaries.size() - 1; i++) {
			int offset = boundaries.get(i);
			String segment = text.substring(offset, boundaries.get(i + 1));
			if (segment.isBlank()) continue;
			for (Document chunk : splitter.apply(List.of(new Document(segment)))) {
				EvidenceLocator.Range local = EvidenceLocator.find(segment, chunk.getText());
				if (local != null) {
					var range = new EvidenceLocator.Range(offset + local.start(), offset + local.end());
					chunk.getMetadata().put("evidenceStart", range.start());
					chunk.getMetadata().put("evidenceEnd", range.end());
					var evidence = EvidenceLocator.at(text, range, locations);
					if (evidence.page() != null) chunk.getMetadata().put("page", evidence.page());
				}
				chunks.add(chunk);
			}
		}
		return chunks;
	}
}
