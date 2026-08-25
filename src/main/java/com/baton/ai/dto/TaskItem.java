package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record TaskItem(
		@JsonPropertyDescription("업무 제목. 예: 가을 정기 할인전 준비")
		String title,
		@JsonPropertyDescription("현재 상태를 짧게. 예: 진행 중, 답변 대기, 매주 반복")
		String status,
		@JsonPropertyDescription("이 업무가 무엇인지 1~2문장 설명")
		String description,
		@JsonPropertyDescription("다음에 해야 할 구체적인 행동")
		String nextAction,
		@JsonPropertyDescription("일정이나 담당자 정보. 알 수 없으면 빈 문자열")
		String schedule) {
}
