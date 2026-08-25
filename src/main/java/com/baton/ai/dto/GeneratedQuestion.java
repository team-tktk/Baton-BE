package com.baton.ai.dto;

import java.util.List;

import com.baton.ai.ClarificationQuestionType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record GeneratedQuestion(
		@JsonPropertyDescription("질문 유형. 자료에 없는 맥락 확인은 INTERVIEW, 자료끼리 값이 다르거나 다를 가능성이 조금이라도 의심되면 CONFLICT")
		ClarificationQuestionType type,
		@JsonPropertyDescription("업로드된 자료만으로는 판단할 수 없어서 확인이 필요한 질문")
		String questionText,
		@JsonPropertyDescription("왜 이걸 확인해야 하는지 1문장 (자료에 여러 방법이 있어서 등)")
		String reason,
		@JsonPropertyDescription("질문을 만든 원문 근거. 파일명과 충돌하거나 부족한 내용을 짧게 요약")
		String evidence,
		@JsonPropertyDescription("객관식 선택지(자료에 근거가 있으면 2~4개). 근거로 삼을 값이 없으면 빈 배열로 두고 자유 답변으로 받으세요 — 자료에 없는 선택지를 지어내지 마세요")
		List<QuestionOption> options) {
}
