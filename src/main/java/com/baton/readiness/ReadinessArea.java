package com.baton.readiness;

import java.util.List;

import com.baton.ai.DraftSection;

/**
 * 준비도 평가 영역. sections는 이 영역의 내용이 담기는 문서 섹션이고, 첫 번째가 보완안의 기본 수정 위치다.
 * 영역별 확인 내용(평가 기준)과 배점은 버전이 있는 ReadinessRubric에서 관리한다.
 */
public enum ReadinessArea {
	SCOPE("업무 범위", List.of(DraftSection.PURPOSE, DraftSection.ONGOING_TASKS, DraftSection.RECURRING_TASKS)),
	PROCEDURE("실행 절차", List.of(DraftSection.RECURRING_TASKS, DraftSection.ONGOING_TASKS,
			DraftSection.FIRST_WEEK_CHECKLIST)),
	COMPLETION("완료 기준", List.of(DraftSection.COMPLETION_CRITERIA)),
	EXCEPTION("예외 대응", List.of(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA)),
	SCHEDULE("일정", List.of(DraftSection.SCHEDULE, DraftSection.RECURRING_TASKS)),
	CONTACTS("담당자", List.of(DraftSection.STAKEHOLDERS, DraftSection.RULES_AND_EXCEPTIONS)),
	ACCESS("접근 권한", List.of(DraftSection.ACCESS_ACCOUNTS)),
	EVIDENCE("근거와 최신성", List.of(DraftSection.TOOLS, DraftSection.CONFIRMED_CRITERIA));

	private final String label;
	private final List<DraftSection> sections;

	ReadinessArea(String label, List<DraftSection> sections) {
		this.label = label;
		this.sections = sections;
	}

	public String getLabel() {
		return label;
	}

	public List<DraftSection> getSections() {
		return sections;
	}

	public DraftSection primarySection() {
		return sections.get(0);
	}

	/**
	 * 문서 섹션이 속한 대표 영역. 그 섹션을 기본 수정 위치로 쓰는 영역을 먼저 고르고, 없으면 섹션을 포함한 첫 영역.
	 * 확인 질문의 반영 위치(targetSections)로 질문을 준비도 영역에 붙일 때 쓴다.
	 */
	public static ReadinessArea of(DraftSection section) {
		for (ReadinessArea area : values()) {
			if (area.primarySection() == section) {
				return area;
			}
		}
		for (ReadinessArea area : values()) {
			if (area.sections.contains(section)) {
				return area;
			}
		}
		throw new IllegalArgumentException("어느 영역에도 속하지 않는 섹션: " + section);
	}

	/** AI가 고른 섹션이 이 영역에 속하지 않으면 기본 섹션으로 바로잡는다. */
	public DraftSection resolveSection(DraftSection candidate) {
		return (candidate != null && sections.contains(candidate)) ? candidate : primarySection();
	}
}
