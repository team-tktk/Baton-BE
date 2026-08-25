package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 답변의 근거 한 건. 프론트는 이걸로 근거 배지를 그리고, 원문/첨부 파일로 이동한다.
 *
 * sourceId == fileId == 업로드 파일(SourceDocument)의 id로, 셋은 항상 같은 값이다:
 *  - 원문 메타데이터 조회: GET /api/v1/handovers/{handoverId}/sources/{sourceId}
 *  - 원본 파일 다운로드:  GET /api/v1/handovers/{handoverId}/files/{fileId}/download
 * fileId를 별도로 두는 건 프론트가 "파일 다운로드"용 id를 의미상 명확히 쓰게 하기 위함이다.
 */
public record Citation(
		UUID sourceId,
		String title,
		String locator,
		UUID fileId,
		Instant updatedAt) {
}
