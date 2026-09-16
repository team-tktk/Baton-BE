package com.baton.readiness.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedFixQuestion(
		@JsonPropertyDescription("확인할 것 하나만 묻는 한 문장, 40자 안팎")
		String question,
		@JsonPropertyDescription("왜 필요한지 한 문장, 25자 안팎")
		String reason) {
}
