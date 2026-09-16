package com.baton.ai;

import java.util.Collection;
import java.util.List;

import com.baton.ai.dto.HandoverDraftContent;

/**
 * 인수인계 문서(HandoverDraftContent)의 섹션. 준비도 평가의 부족 항목이 가리키는 문서 위치이고,
 * 보완안을 적용할 때 문서 전체가 아니라 이 섹션 하나만 바꾸는 단위다.
 */
public enum DraftSection {
	PURPOSE("purpose", "업무 개요"),
	COMPLETION_CRITERIA("completionCriteria", "완료 기준"),
	ONGOING_TASKS("ongoingTasks", "진행 중인 업무"),
	RECURRING_TASKS("recurringTasks", "반복 업무"),
	RULES_AND_EXCEPTIONS("rulesAndExceptions", "업무 기준과 예외"),
	STAKEHOLDERS("stakeholders", "주요 관계자"),
	TOOLS("tools", "사용 도구와 자료"),
	SCHEDULE("schedule", "업무 일정"),
	ACCESS_ACCOUNTS("accessAccounts", "접근 권한과 계정"),
	FIRST_WEEK_CHECKLIST("firstWeekChecklist", "첫 주 체크리스트"),
	CONFIRMED_CRITERIA("confirmedCriteria", "확인된 업무 기준");

	private static final HandoverDraftContent EMPTY = new HandoverDraftContent(
			null, null, null, null, null, null, null, null, null, null, null);

	private final String fieldName;
	private final String label;

	DraftSection(String fieldName, String label) {
		this.fieldName = fieldName;
		this.label = label;
	}

	public String getFieldName() {
		return fieldName;
	}

	public String getLabel() {
		return label;
	}

	/** 이 섹션의 값(String 또는 List). content가 null이면 null. */
	public Object valueOf(HandoverDraftContent content) {
		if (content == null) {
			return null;
		}
		return switch (this) {
			case PURPOSE -> content.purpose();
			case COMPLETION_CRITERIA -> content.completionCriteria();
			case ONGOING_TASKS -> content.ongoingTasks();
			case RECURRING_TASKS -> content.recurringTasks();
			case RULES_AND_EXCEPTIONS -> content.rulesAndExceptions();
			case STAKEHOLDERS -> content.stakeholders();
			case TOOLS -> content.tools();
			case SCHEDULE -> content.schedule();
			case ACCESS_ACCOUNTS -> content.accessAccounts();
			case FIRST_WEEK_CHECKLIST -> content.firstWeekChecklist();
			case CONFIRMED_CRITERIA -> content.confirmedCriteria();
		};
	}

	/** 섹션에 내용이 하나도 없는가(null·공백 문자열·빈 목록). */
	public boolean isEmpty(HandoverDraftContent content) {
		Object value = valueOf(content);
		if (value == null) {
			return true;
		}
		if (value instanceof String text) {
			return text.isBlank();
		}
		return value instanceof Collection<?> items && items.isEmpty();
	}

	/** 나열한 섹션이 모두 비어 있는가. */
	public static boolean allEmpty(HandoverDraftContent content, Collection<DraftSection> sections) {
		return sections.stream().allMatch(section -> section.isEmpty(content));
	}

	/** base에서 이 섹션만 patch 값으로 바꾼 새 content. 나머지 섹션(사람이 고친 내용 포함)은 그대로 둔다. */
	public HandoverDraftContent merge(HandoverDraftContent base, HandoverDraftContent patch) {
		return new HandoverDraftContent(
				this == PURPOSE ? patch.purpose() : base.purpose(),
				this == COMPLETION_CRITERIA ? patch.completionCriteria() : base.completionCriteria(),
				this == ONGOING_TASKS ? orEmpty(patch.ongoingTasks()) : base.ongoingTasks(),
				this == RECURRING_TASKS ? orEmpty(patch.recurringTasks()) : base.recurringTasks(),
				this == RULES_AND_EXCEPTIONS ? orEmpty(patch.rulesAndExceptions()) : base.rulesAndExceptions(),
				this == STAKEHOLDERS ? orEmpty(patch.stakeholders()) : base.stakeholders(),
				this == TOOLS ? orEmpty(patch.tools()) : base.tools(),
				this == SCHEDULE ? orEmpty(patch.schedule()) : base.schedule(),
				this == ACCESS_ACCOUNTS ? orEmpty(patch.accessAccounts()) : base.accessAccounts(),
				this == FIRST_WEEK_CHECKLIST ? orEmpty(patch.firstWeekChecklist()) : base.firstWeekChecklist(),
				this == CONFIRMED_CRITERIA ? orEmpty(patch.confirmedCriteria()) : base.confirmedCriteria());
	}

	/** content에서 이 섹션만 남긴 content(나머지는 null). 수정 전후 스냅샷 저장용. */
	public HandoverDraftContent only(HandoverDraftContent content) {
		return merge(EMPTY, content);
	}

	private static <T> List<T> orEmpty(List<T> items) {
		return items == null ? List.of() : items;
	}
}
