package com.baton.ai;

/**
 * 업로드 파일 처리 상태.
 *
 * 마스킹 검수 꺼짐(app.masking.enabled=false): EXTRACTING → INDEXED / FAILED
 * 마스킹 검수 켜짐: EXTRACTING → MASKING_REVIEW → (검수 확정) INDEXING → INDEXED / FAILED
 */
public enum SourceDocumentStatus {
	EXTRACTING,
	MASKING_REVIEW,  // 텍스트 추출·후보 탐지 완료, 사용자 검수 대기. 아직 임베딩(외부 전송) 전.
	INDEXING,        // 검수 확정 후 마스킹된 텍스트로 임베딩 중
	INDEXED,
	FAILED
}
