package com.baton.ai.dto;

import java.time.Instant;
import java.util.List;

import com.baton.ai.HandoverDraft;

/**
 * 인수자용 첫날 요약 화면. 초안(document) 전체가 아니라 "오늘 바로 필요한 것" 위주로 추린 응답이다.
 * summary는 AI가 초안 내용을 바탕으로 새로 쓴 환영 브리핑 문장, 나머지는 초안에서 그대로 가져온 값이다.
 */
public record HandoverBriefingResponse(
		String summary,
		String purpose,
		String completionCriteria,
		List<String> firstWeekChecklist,
		List<AccessItem> accessAccounts,
		List<Stakeholder> stakeholders,
		Instant updatedAt) {

	public static HandoverBriefingResponse from(HandoverDraft draft) {
		HandoverDraftContent content = draft.getContent();
		return new HandoverBriefingResponse(
				draft.getBriefingSummary(),
				content.purpose(),
				content.completionCriteria(),
				content.firstWeekChecklist(),
				content.accessAccounts(),
				content.stakeholders(),
				draft.getUpdatedAt());
	}
}
