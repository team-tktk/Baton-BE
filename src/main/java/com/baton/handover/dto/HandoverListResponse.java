package com.baton.handover.dto;

import java.util.List;
import java.util.Map;

import com.baton.handover.HandoverStatus;

/**
 * 인수인계 목록 응답. 커서 페이지네이션 + 상태별 개수(필터 탭 뱃지용)를 함께 내린다.
 * statusCounts는 모든 상태를 포함하며 해당 없는 상태는 0으로 채운다.
 */
public record HandoverListResponse(
		List<HandoverSummaryResponse> items,
		String nextCursor,
		boolean hasNext,
		Map<HandoverStatus, Long> statusCounts) {
}
