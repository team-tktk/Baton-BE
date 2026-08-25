package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** LLM 구조화 출력 스키마. 초안과, 자료만으로 판단 안 되는 부분에 대한 확인 질문을 한 번에 생성한다. */
public record AnalysisResult(
		HandoverDraftContent draft,
		@JsonPropertyDescription("자료만으로 확신할 수 없는 부분에 대한 확인 질문 0~5개. 애매한 게 없으면 빈 배열")
		List<GeneratedQuestion> questions) {
}
