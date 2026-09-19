package com.baton.readiness.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedEvidence(
		@JsonPropertyDescription("업로드 파일명(자료 목록에 있는 이름 그대로)")
		String fileName,
		@JsonPropertyDescription("파일 안 위치를 짧게. 예: 3번 시트, 예외 상황 / 2장 환불 절차. 모르면 빈 문자열")
		String locator,
		@JsonPropertyDescription("판단에 사용한 자료 본문의 실제 문장 그대로 인용. 요약하거나 만들어내지 말 것. 없으면 null")
		String quote) {
	public GeneratedEvidence(String fileName, String locator) {
		this(fileName, locator, null);
	}
}
