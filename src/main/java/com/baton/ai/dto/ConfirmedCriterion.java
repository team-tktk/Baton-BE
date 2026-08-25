package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 확인 질문 답변으로 확정된 업무 기준 한 줄. 예: label="쿠폰 승인", value="팀장에게 바로".
 * 인계자가 확인 질문에 답한 내용에서 뽑아낸 명확한 판단 기준.
 */
public record ConfirmedCriterion(
		@JsonPropertyDescription("기준 항목 이름. 예: 쿠폰 승인, 배송업체 미회신, 주문 현황 공유일")
		String label,
		@JsonPropertyDescription("그 항목에 대해 확정된 답. 예: 팀장에게 바로, 오늘 오후 3시, 수요일")
		String value) {
}
