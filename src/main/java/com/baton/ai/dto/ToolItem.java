package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 사용 도구와 자료 한 줄. 예: "주간 주문 현황 양식.xlsx — 매주 주문과 반품 기록". */
public record ToolItem(
		@JsonPropertyDescription("자료·도구 이름. 업로드 파일이면 파일명")
		String name,
		@JsonPropertyDescription("무엇에 쓰는 자료인지 한 줄 설명")
		String description) {
}
