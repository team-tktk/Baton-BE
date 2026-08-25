package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record QuestionOption(
		@JsonPropertyDescription("선택지 제목. 예: 마케팅 → 팀장")
		String label,
		@JsonPropertyDescription("이 선택지를 고르면 실제로 어떻게 하는지 짧은 설명")
		String description) {
}
