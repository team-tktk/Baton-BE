package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record Stakeholder(
		@JsonPropertyDescription("이름")
		String name,
		@JsonPropertyDescription("소속 팀")
		String team,
		@JsonPropertyDescription("이 사람에게 도움을 받을 수 있는 내용")
		String helpWith) {
}
