package com.baton.aiusage;

/**
 * 요청 횟수를 제한하는 AI 기능. 한도는 세 기능을 합산해서 센다(기능 구분은 기록용).
 * 목적은 비정상적인 연속 호출 차단이라, 분석(확인 질문 생성)·준비도 평가처럼 결과를 재사용하거나
 * 상태로 막히는 기능은 넣지 않는다.
 */
public enum AiFeature {
	DRAFT_GENERATION("인수인계서 생성"),
	READINESS_FIX("보완안 생성"),
	CHAT("채팅");

	private final String label;

	AiFeature(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
