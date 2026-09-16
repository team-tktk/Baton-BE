package com.baton.readiness;

/** 영역 하나의 평가 결과. AI는 이 넷 중 하나만 고르고, 점수 환산은 서버가 ReadinessRubric으로 한다. */
public enum ReadinessStatus {
	SUFFICIENT("충분"),
	PARTIAL("일부 부족"),
	MISSING("누락"),
	CONFLICT("충돌");

	private final String label;

	ReadinessStatus(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
