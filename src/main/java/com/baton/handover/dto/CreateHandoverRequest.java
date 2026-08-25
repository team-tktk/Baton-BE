package com.baton.handover.dto;

import java.util.List;
import java.util.UUID;

/**
 * 인수인계 초안 생성 요청. 초안 단계라 모든 필드가 선택이다 —
 * 인계자 화면에서 먼저 빈 초안을 만들고 이후 PATCH로 채워도 된다.
 * null인 리스트는 빈 목록으로 취급한다.
 */
public record CreateHandoverRequest(
		String title,
		List<UUID> recipientIds,
		List<WorkScopeInput> workScopes) {
}
