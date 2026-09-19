package com.baton.ai;

/**
 * 확인 질문 처리 상태. 모름·해당 없음·나중에 답하기는 모두 "답이 없는" 상태지만 문서에 반영되는 방식이 다르다.
 * - UNKNOWN: 인계자도 모름 → 추측하지 않고 인수자가 확인할 항목으로 남긴다.
 * - NOT_APPLICABLE: 이 업무엔 해당 없음 → 해당 항목을 문서에 만들지 않는다.
 * - DEFERRED: 나중에 답하기 → 지금은 반영하지 않고, 답이 달리면 반영 API로 관련 섹션만 갱신한다.
 */
public enum ClarificationQuestionStatus {
	PENDING,
	ANSWERED,
	UNKNOWN,
	NOT_APPLICABLE,
	DEFERRED;

	/** 문서에 반영할 내용이 있는 상태(답변·모름·해당 없음). */
	public boolean isResolved() {
		return this == ANSWERED || this == UNKNOWN || this == NOT_APPLICABLE;
	}
}
