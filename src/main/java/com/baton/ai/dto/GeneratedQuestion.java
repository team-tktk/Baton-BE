package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedQuestion(
		@JsonPropertyDescription("업로드된 자료만으로는 판단할 수 없어서 확인이 필요한 질문")
		String questionText,
		@JsonPropertyDescription("왜 이걸 확인해야 하는지 1문장 (자료에 여러 방법이 있어서 등)")
		String reason,
		@JsonPropertyDescription("객관식 선택지 2~4개. 자료에서 근거를 찾은 그럴듯한 선택지로 구성")
		List<QuestionOption> options) {
}
