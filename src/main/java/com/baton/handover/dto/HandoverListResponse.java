package com.baton.handover.dto;

import java.util.List;
import java.util.Map;

/**
 * 인수인계 목록 응답. 커서 페이지네이션 + 필터 탭 뱃지용 개수를 함께 내린다.
 * statusCounts의 키는 목록 종류에 따라 다르다: sent/reviews는 HandoverStatus 이름,
 * received는 ReceivedFilter 이름(UNREAD/IN_PROGRESS/COMPLETED). 모든 키를 포함하며 없으면 0.
 */
public record HandoverListResponse(
		List<HandoverSummaryResponse> items,
		String nextCursor,
		boolean hasNext,
		Map<String, Long> statusCounts) {
}
