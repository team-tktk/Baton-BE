package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** LLM 구조화 출력 스키마. 초안과, 자료만으로 판단 안 되는 부분에 대한 확인 질문을 한 번에 생성한다. */
public record AnalysisResult(
		HandoverDraftContent draft,
		@JsonPropertyDescription("인계자에게 물어볼 확인 질문. 반드시 최소 1개 이상(빈 배열 금지). 자료 간 값이 충돌하거나 충돌 가능성이 조금이라도(약 30%↑) 의심되면 반드시 CONFLICT로 포함. 보통 1~6개")
		List<GeneratedQuestion> questions) {
}
