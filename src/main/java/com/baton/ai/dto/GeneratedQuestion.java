package com.baton.ai.dto;

import java.util.List;

import com.baton.ai.ClarificationQuestionType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedQuestion(
		@JsonPropertyDescription("질문 유형. 자료에 없는 맥락 확인은 INTERVIEW, 자료끼리 충돌하면 CONFLICT")
		ClarificationQuestionType type,
		@JsonPropertyDescription("업로드된 자료만으로는 판단할 수 없어서 확인이 필요한 질문")
		String questionText,
		@JsonPropertyDescription("왜 이걸 확인해야 하는지 1문장 (자료에 여러 방법이 있어서 등)")
		String reason,
		@JsonPropertyDescription("질문을 만든 원문 근거. 파일명과 충돌하거나 부족한 내용을 짧게 요약")
		String evidence,
		@JsonPropertyDescription("객관식 선택지 2~4개. 자료에서 근거를 찾은 그럴듯한 선택지로 구성")
		List<QuestionOption> options) {
}
