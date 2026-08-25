package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * AI가 업로드 문서로부터 생성한 구조화된 인수인계 초안.
 * 필드 순서 = 내보내기 문서의 고정 섹션 순서(업무 개요 → … → 확인된 업무 기준).
 */
public record HandoverDraftContent(
		@JsonPropertyDescription("이 업무를 왜 하는지, 업무 목적 1~2문장 (## 업무 개요)")
		String purpose,
		@JsonPropertyDescription("후임자가 무엇을 할 수 있어야 인수인계가 끝난 것으로 볼지")
		String completionCriteria,
		@JsonPropertyDescription("지금 진행 중이거나 답변을 기다리는 등 아직 끝나지 않은 업무 목록 (## 진행 중인 업무)")
		List<TaskItem> ongoingTasks,
		@JsonPropertyDescription("매주/매월처럼 정해진 주기로 반복하는 업무 목록 (## 반복 업무)")
		List<TaskItem> recurringTasks,
		@JsonPropertyDescription("담당자가 바뀌어도 같은 판단을 내리기 위한 기준·예외 규칙 목록. 문장 단위 (## 업무 기준과 예외)")
		List<String> rulesAndExceptions,
		@JsonPropertyDescription("업무별로 도움을 받을 수 있는 주요 관계자 목록 (## 주요 관계자)")
		List<Stakeholder> stakeholders,
		@JsonPropertyDescription("업무에 쓰는 자료·도구 목록. 업로드된 파일과 그 용도 (## 사용 도구와 자료)")
		List<ToolItem> tools,
		@JsonPropertyDescription("정해진 주기로 반복되는 업무 일정 목록 (## 업무 일정)")
		List<ScheduleItem> schedule,
		@JsonPropertyDescription("업무 수행에 필요한 시스템 접근 권한과 계정 상태 목록 (## 접근 권한과 계정)")
		List<AccessItem> accessAccounts,
		@JsonPropertyDescription("인수자가 첫 주에 확인해야 할 체크리스트 항목. 문장 단위 (## 첫 주 체크리스트)")
		List<String> firstWeekChecklist,
		@JsonPropertyDescription("확인 질문 답변으로 확정된 업무 판단 기준 목록 (## 확인된 업무 기준)")
		List<ConfirmedCriterion> confirmedCriteria) {
}
