package com.baton.ai;

import java.util.EnumSet;

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

	/** 아직 끝나지 않아 "진행 중"으로 취급하는 상태 전부. 고착 작업 탐지에도 재사용한다. */
	public static EnumSet<AnalysisJobStatus> active() {
		EnumSet<AnalysisJobStatus> statuses = EnumSet.allOf(AnalysisJobStatus.class);
		statuses.removeIf(AnalysisJobStatus::isTerminal);
		return statuses;
	}
}
