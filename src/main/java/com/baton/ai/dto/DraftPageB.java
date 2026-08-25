package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 초안 페이지 B(사람·자원·온보딩). HandoverDraftContent의 뒷부분 섹션만 담아 별도 LLM 호출로 생성한다.
 * 페이지 A와 병렬로 생성해 전체 초안 생성 시간을 줄인다.
 */
public record DraftPageB(
		@JsonPropertyDescription("업무별로 도움을 받을 수 있는 주요 관계자 목록")
		List<Stakeholder> stakeholders,
		@JsonPropertyDescription("업무에 쓰는 자료·도구 목록. 업로드된 파일과 그 용도")
		List<ToolItem> tools,
		@JsonPropertyDescription("정해진 주기로 반복되는 업무 일정 목록")
		List<ScheduleItem> schedule,
		@JsonPropertyDescription("업무 수행에 필요한 시스템 접근 권한과 계정 상태 목록")
		List<AccessItem> accessAccounts,
		@JsonPropertyDescription("인수자가 첫 주에 확인해야 할 체크리스트 항목. 문장 단위")
		List<String> firstWeekChecklist,
		@JsonPropertyDescription("확인 질문 답변으로 확정된 업무 판단 기준 목록")
		List<ConfirmedCriterion> confirmedCriteria) {
}
