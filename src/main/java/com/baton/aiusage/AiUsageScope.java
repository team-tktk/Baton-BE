package com.baton.aiusage;

/** 한도를 세는 단위. 조직은 회원의 소속 팀(users.team) 기준이다. */
public enum AiUsageScope {
	USER("사용자"),
	ORGANIZATION("조직");

	private final String label;

	AiUsageScope(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}
}
