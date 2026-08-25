package com.baton.ai.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 업무 일정 한 줄. 예: "매주 월요일 / 주문 현황 집계 / 반품과 문의 포함해 공유". */
public record ScheduleItem(
		@JsonPropertyDescription("주기. 예: 매일, 매주 월요일, 매월 말")
		String cycle,
		@JsonPropertyDescription("해당 주기에 하는 업무. 예: 주문 현황 집계")
		String task,
		@JsonPropertyDescription("업무 세부 설명이나 유의사항. 알 수 없으면 빈 문자열")
		String detail) {
}
