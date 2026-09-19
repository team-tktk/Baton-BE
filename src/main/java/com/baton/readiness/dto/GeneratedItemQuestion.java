package com.baton.readiness.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedItemQuestion(
		@JsonPropertyDescription("확인할 것 하나만 묻는 한 문장, 40자 안팎")
		String question,
		@JsonPropertyDescription("왜 필요한지 한 문장, 25자 안팎")
		String reason,
		@JsonPropertyDescription("고를 수 있는 값. 충돌이면 자료마다 적힌 값(예: 'A.pdf: 팀장 승인'). 근거가 없으면 빈 배열")
		List<String> options) {
}
