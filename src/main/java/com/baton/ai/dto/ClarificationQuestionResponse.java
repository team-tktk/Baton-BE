package com.baton.ai.dto;

import java.util.List;
import java.util.UUID;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionType;
import com.baton.ai.DraftSection;

public record ClarificationQuestionResponse(
		UUID id,
		ClarificationQuestionType type,
		String questionText,
		String reason,
		String evidence,
		List<QuestionOption> options,
		List<TargetSection> targetSections,
		Integer priority,
		String status,
		String answer,
		boolean applied) {

	public static ClarificationQuestionResponse from(ClarificationQuestion question) {
		return new ClarificationQuestionResponse(
				question.getId(),
				question.getType(),
				question.getQuestionText(),
				question.getReason(),
				question.getEvidence(),
				question.getOptions(),
				question.getTargetSections().stream().map(TargetSection::from).toList(),
				question.getPriority(),
				question.getStatus().name(),
				question.getAnswer(),
				question.getAppliedAt() != null);
	}

	/** 답변이 반영될 문서 위치. section은 문서 JSON 필드와 매칭용, label은 화면 표시용. */
	public record TargetSection(DraftSection section, String field, String label) {

		static TargetSection from(DraftSection section) {
			return new TargetSection(section, section.getFieldName(), section.getLabel());
		}
	}
}
