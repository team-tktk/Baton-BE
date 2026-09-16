package com.baton.readiness;

public enum ReadinessGrade {
	READY("준비 완료"),
	NEEDS_IMPROVEMENT("보완 필요"),
	NOT_READY("준비 부족");

	private final String label;

	ReadinessGrade(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
