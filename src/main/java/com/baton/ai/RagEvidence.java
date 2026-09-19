package com.baton.ai;

import java.time.Instant;
import org.springframework.ai.document.Document;
import com.baton.ai.dto.Citation;

record RagEvidence(Document document, Citation citation, int sourcePriority, Instant updatedAt) {
	String context() {
		return "[자료 유형: %s | 제목: %s | 위치: %s | 갱신: %s]\n%s".formatted(
				citation.type(), citation.title(), citation.locator(), updatedAt, document.getText());
	}
}
