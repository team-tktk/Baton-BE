package com.baton.aiusage;

/** 같은 인수인계에서 동시에 하나만 실행하는 AI 작업(중복 클릭 방지). 횟수 한도(AiFeature)와는 별개다. */
public enum AiTask {
	ANALYSIS("분석"),
	DRAFT_GENERATION("인수인계서 생성"),
	READINESS_EVALUATION("준비도 평가"),
	READINESS_FIX("보완안 생성");

	private final String label;

	AiTask(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
