package com.baton.readiness;

/**
 * 부족 항목 보완안의 상태.
 * NEEDS_INPUT(자료에 답이 없어 추가 질문 답변 대기) → PROPOSED(수정 전후 비교 대기) → APPLIED / DISCARDED
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
