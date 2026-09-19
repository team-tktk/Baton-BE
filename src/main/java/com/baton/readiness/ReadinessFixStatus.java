package com.baton.readiness;

/**
 * 보완안의 상태.
 * NEEDS_INPUT(수정안 없음 — 질문에 답하고 보완안 만들기 대기) → PROPOSED(수정안이 있는 영역이 하나 이상, 수정 전후 비교 대기)
 * → APPLIED / DISCARDED. 두 열린 상태 모두에서 답을 고치고 보완안을 다시 만들 수 있다.
 */
public enum ReadinessFixStatus {
	NEEDS_INPUT,
	PROPOSED,
	APPLIED,
	DISCARDED;

	public boolean isOpen() {
		return this == NEEDS_INPUT || this == PROPOSED;
	}
}
