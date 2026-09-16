package com.baton.readiness.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedAssessment(
		@JsonPropertyDescription("평가 영역마다 정확히 한 개씩의 평가 결과")
		List<GeneratedAreaAssessment> areas) {
}
