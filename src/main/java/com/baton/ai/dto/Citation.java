package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

/**
 * AI 답변의 근거 한 건. 프론트는 이걸로 근거 배지를 그리고, 원문/첨부 파일로 이동한다.
 *
 * 파일 근거는 sourceId == fileId이고, 웹/Slack 근거는 fileId가 null이며 type/url로 원문을 연다.
 *  - 원문 메타데이터 조회: GET /api/v1/handovers/{handoverId}/sources/{sourceId}
 *  - 원본 파일 다운로드:  GET /api/v1/handovers/{handoverId}/files/{fileId}/download
 * fileId를 별도로 두는 건 프론트가 "파일 다운로드" 가능 여부를 명확히 구분하게 하기 위함이다.
 */
public record Citation(
		UUID sourceId,
		String title,
		String locator,
		UUID fileId,
		Instant updatedAt,
		String type,
		String url,
		Integer page,
		String quote,
		List<EvidenceHighlight> highlights) {
	public Citation {
		highlights = highlights == null ? List.of() : List.copyOf(highlights);
	}

	public Citation(UUID sourceId, String title, String locator, UUID fileId, Instant updatedAt, String type, String url) {
		this(sourceId, title, locator, fileId, updatedAt, type, url, null, null, List.of());
	}
}
