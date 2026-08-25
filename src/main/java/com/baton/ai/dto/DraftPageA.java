package com.baton.ai.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 초안 페이지 A(핵심 업무). HandoverDraftContent의 앞부분 섹션만 담아 별도 LLM 호출로 생성한다.
 * 페이지를 나눠 병렬 생성하면 각 호출의 출력이 작아져 전체 생성이 빨라진다.
 */
public record DraftPageA(
		@JsonPropertyDescription("이 업무를 왜 하는지, 업무 목적 1~2문장")
		String purpose,
		@JsonPropertyDescription("후임자가 무엇을 할 수 있어야 인수인계가 끝난 것으로 볼지")
		String completionCriteria,
		@JsonPropertyDescription("지금 진행 중이거나 답변을 기다리는 등 아직 끝나지 않은 업무 목록")
		List<TaskItem> ongoingTasks,
		@JsonPropertyDescription("매주/매월처럼 정해진 주기로 반복하는 업무 목록")
		List<TaskItem> recurringTasks,
		@JsonPropertyDescription("담당자가 바뀌어도 같은 판단을 내리기 위한 기준·예외 규칙 목록. 문장 단위")
		List<String> rulesAndExceptions) {
}
