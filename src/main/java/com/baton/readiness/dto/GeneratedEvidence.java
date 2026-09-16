package com.baton.readiness.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedEvidence(
		@JsonPropertyDescription("업로드 파일명(자료 목록에 있는 이름 그대로)")
		String fileName,
		@JsonPropertyDescription("파일 안 위치를 짧게. 예: 3번 시트, 예외 상황 / 2장 환불 절차. 모르면 빈 문자열")
		String locator) {
}
