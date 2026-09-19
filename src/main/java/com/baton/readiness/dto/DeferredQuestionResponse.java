package com.baton.readiness.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionType;
import com.baton.ai.dto.ClarificationQuestionResponse.TargetSection;
import com.baton.readiness.ReadinessArea;

/**
 * 확인 질문 단계에서 "나중에 답하기"로 미룬 질문. 준비도 화면에 표시만 하고 점수에는 반영하지 않는다.
 * 답은 확인 질문 API(PUT /questions/{questionId}/answer → POST /questions/apply)로 한다.
 *
 * @param area 질문이 붙는 준비도 영역. 첫 번째 반영 위치(targetSections)가 속한 영역.
 */
public record DeferredQuestionResponse(
		UUID id,
		ClarificationQuestionType type,
		String questionText,
		String reason,
		ReadinessArea area,
		List<TargetSection> targetSections) {

	public static DeferredQuestionResponse from(ClarificationQuestion question, ReadinessArea area) {
		return new DeferredQuestionResponse(
				question.getId(),
				question.getType(),
				question.getQuestionText(),
				question.getReason(),
				area,
				question.getTargetSections().stream()
						.map(section -> new TargetSection(section, section.getFieldName(), section.getLabel()))
						.toList());
	}
}
