package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 접근 권한과 계정 한 줄. 예: "운영 어드민 / 주문 조회·행사 설정 / 사용 가능". */
public record AccessItem(
		@JsonPropertyDescription("도구·시스템 이름. 예: 운영 어드민, 공유 드라이브")
		String tool,
		@JsonPropertyDescription("필요한 권한. 예: 주문 조회·행사 설정")
		String permission,
		@JsonPropertyDescription("현재 상태. 예: 사용 가능, 초대 필요")
		String status) {
}
