package com.baton.ai;

import java.util.List;
import org.springframework.ai.document.Document;
import com.baton.ai.dto.Citation;

/** Build evidence exclusively from the current, safe source text. */
public final class EvidenceCitations {
	private EvidenceCitations() { }

	public static Citation resolve(SourceDocument source, String quote) {
		EvidenceLocator.Located located = source.isEnabled() && source.getStatus() == SourceDocumentStatus.INDEXED
				? EvidenceLocator.locate(source.getExtractedText(), quote, source.getPdfTextLocations())
				: new EvidenceLocator.Located(null, null, List.of());
		return citation(source, located, section(source));
	}

	public static Citation fromMatch(SourceDocument source, Document match) {
		String text = source.getExtractedText();
		EvidenceLocator.Range range = EvidenceLocator.find(text, match.getText());
		// Persisted offsets disambiguate identical excerpts only when they still match the safe text.
		Object start = match.getMetadata().get("evidenceStart");
		Object end = match.getMetadata().get("evidenceEnd");
		if (text != null && start instanceof Number s && end instanceof Number e
				&& s.intValue() >= 0 && e.intValue() > s.intValue() && e.intValue() <= text.length()
				&& EvidenceLocator.find(text.substring(s.intValue(), e.intValue()), match.getText()) != null) {
			range = new EvidenceLocator.Range(s.intValue(), e.intValue());
		}
		EvidenceLocator.Located located = range == null
				? new EvidenceLocator.Located(null, null, List.of())
				: EvidenceLocator.at(text, range, source.getPdfTextLocations());
		Object index = match.getMetadata().get("chunkIndex");
		Object heading = match.getMetadata().get("sectionHeading");
		String locator = heading instanceof String value && !value.isBlank() ? value
				: index instanceof Number n ? "청크 " + (n.intValue() + 1) : section(source);
		return citation(source, located, locator);
	}

	private static Citation citation(SourceDocument source, EvidenceLocator.Located located, String fallback) {
		return new Citation(source.getId(), source.getFileName(),
				located.page() == null ? fallback : located.page() + "페이지",
				source.getSourceType() == SourceType.FILE ? source.getId() : null,
				source.getUpdatedAt(), source.getSourceType().name(), source.getOriginalUrl(),
				located.page(), located.quote(), located.highlights());
	}

	private static String section(SourceDocument source) {
		return source.getConversationName() != null ? source.getConversationName() : "문서 본문";
	}
}
