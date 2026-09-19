package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 질문 전용 생성 결과(초안과 분리). 분석 단계에서 이것만 빠르게 만들어 먼저 보여주고,
 * 초안은 답변을 받은 뒤 별도로(페이지 병렬) 생성한다.
 */
public record GeneratedQuestions(
		@JsonPropertyDescription("인계자에게 물어볼 확인 질문. 중요한 것만 최대 5개. 자료에 답이 있는 질문, 이미 물어본 질문과 같은 뜻의 질문은 넣지 말 것. "
				+ "자료 간 값이 충돌하거나 충돌 가능성이 조금이라도(약 30%↑) 의심되면 CONFLICT로 포함")
		List<GeneratedQuestion> questions) {
}
