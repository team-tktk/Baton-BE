package com.baton.readiness;

import java.util.UUID;
import java.util.List;
import com.baton.ai.dto.EvidenceHighlight;

/**
 * 평가·보완안의 근거 한 건. sourceId는 업로드 파일(SourceDocument) id로,
 * 원본은 GET /files/{sourceId}/download, 메타데이터는 GET /sources/{sourceId}로 연다.
 *
 * @param locator 파일 안 위치. 예: "3번 시트, 예외 상황", "청크 2/8"
 */
public record ReadinessEvidence(
		UUID sourceId,
		String fileName,
		String locator,
		Integer page,
		String quote,
		List<EvidenceHighlight> highlights) {
	public ReadinessEvidence {
		highlights = highlights == null ? List.of() : List.copyOf(highlights);
	}
	public ReadinessEvidence(UUID sourceId, String fileName, String locator) {
		this(sourceId, fileName, locator, null, null, List.of());
	}
}
