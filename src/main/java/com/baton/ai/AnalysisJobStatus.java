package com.baton.ai;

/** AI 분석 작업의 진행 상태. 프론트 폴링 응답에 enum 이름을 그대로 사용한다. */
public enum AnalysisJobStatus {

	QUEUED,
	PARSING,
	INDEXING,
	GENERATING_QUESTIONS,
	GENERATING_DRAFT,
	COMPLETED,
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}
}
