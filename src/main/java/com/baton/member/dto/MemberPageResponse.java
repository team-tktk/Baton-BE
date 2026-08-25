package com.baton.member.dto;

import java.util.List;

/**
 * 커서 페이지네이션 응답. nextCursor를 다음 요청의 cursor로 그대로 넘기면 이어서 조회된다.
 * 더 없으면 hasNext=false, nextCursor=null.
 */
public record MemberPageResponse(
		List<MemberResponse> items,
		String nextCursor,
		boolean hasNext) {
}
