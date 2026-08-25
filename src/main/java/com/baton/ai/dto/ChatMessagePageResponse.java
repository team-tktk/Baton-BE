package com.baton.ai.dto;

import java.util.List;

/** 대화 이력 커서 페이지네이션 응답. nextCursor는 마지막 메시지의 createdAt(ISO-8601)이다. */
public record ChatMessagePageResponse(
		List<ChatMessageResponse> items,
		String nextCursor,
		boolean hasNext) {
}
