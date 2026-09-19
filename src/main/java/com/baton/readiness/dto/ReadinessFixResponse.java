package com.baton.readiness.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.ClarificationQuestionResponse.TargetSection;
import com.baton.readiness.FixAreaResult;
import com.baton.readiness.FixQuestion;
import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessEvidence;
import com.baton.readiness.ReadinessFix;
import com.baton.readiness.ReadinessFixStatus;
import com.baton.readiness.ReadinessStatus;

/**
 * 여러 부족 영역을 한 번에 보완하는 보완안.
 *
 * @param baseRevision     보완을 시작할 때의 문서 버전. 적용 요청에 그대로 보낸다.
 * @param stale            그사이 문서가 바뀌어 이 보완안을 적용할 수 없으면 true — 새로 시작해야 한다.
 * @param areas            보완할 영역별 결과와 질문(요청한 순서).
 * @param sections         고칠 수 있는 섹션별 수정 전후. 수정안이 없으면 after는 null.
 * @param unansweredCount  아직 답하지 않은 질문 수. 0이 아니어도 보완안을 만들 수 있다(자료로 채울 수 있는 영역도 있으므로).
 */
public record ReadinessFixResponse(
		UUID fixId,
		ReadinessFixStatus status,
		long baseRevision,
		boolean stale,
		Long appliedRevision,
		List<AreaFix> areas,
		List<SectionChange> sections,
		int unansweredCount,
		Instant createdAt,
		Instant updatedAt) {

	/**
	 * @param status    보완을 시작할 때의 평가 상태. CONFLICT면 "어느 쪽이 맞나요?" 질문에 답해야 수정안이 나온다.
	 * @param proposed  이 영역의 수정안이 있는가. 적용하면 이 영역의 섹션만 문서에 반영된다.
	 * @param questions 이 영역의 질문. clarificationQuestionId가 있으면 확인 질문 단계에서 "나중에 답하기"로 미룬 질문이다.
	 */
	public record AreaFix(
			ReadinessArea area,
			String areaLabel,
			ReadinessStatus status,
			String statusLabel,
			List<TargetSection> sections,
			boolean proposed,
			String changeSummary,
			List<ReadinessEvidence> evidence,
			List<FixQuestion> questions) {
	}

	/**
	 * @param before  수정 전 값(섹션에 따라 문자열 또는 배열). 문서 조회 응답의 content.{field}와 같은 형식.
	 * @param after   수정 후 값. 수정안이 없으면 null.
	 * @param changed 수정안이 이 섹션을 바꾸는가.
	 */
	public record SectionChange(
			DraftSection section,
			String field,
			String label,
			Object before,
			Object after,
			boolean changed) {
	}

	public static ReadinessFixResponse of(ReadinessFix fix, long currentRevision) {
		List<AreaFix> areas = fix.getAreaResults().stream()
				.map(result -> areaFix(result, fix.getQuestions()))
				.toList();
		List<SectionChange> sections = fix.getSections().stream()
				.map(section -> sectionChange(section, fix))
				.toList();
		int unanswered = (int) fix.getQuestions().stream().filter(question -> !question.hasAnswer()).count();
		return new ReadinessFixResponse(
				fix.getId(),
				fix.getStatus(),
				fix.getBaseRevision(),
				fix.getStatus().isOpen() && fix.isStaleAgainst(currentRevision),
				fix.getAppliedRevision(),
				areas,
				sections,
				unanswered,
				fix.getCreatedAt(),
				fix.getUpdatedAt());
	}

	private static AreaFix areaFix(FixAreaResult result, List<FixQuestion> questions) {
		return new AreaFix(
				result.area(),
				result.area().getLabel(),
				result.status(),
				result.status() == null ? null : result.status().getLabel(),
				result.sections().stream()
						.map(section -> new TargetSection(section, section.getFieldName(), section.getLabel()))
						.toList(),
				result.proposed(),
				result.changeSummary(),
				result.evidence() == null ? List.of() : result.evidence(),
				questions.stream().filter(question -> question.area() == result.area()).toList());
	}

	private static SectionChange sectionChange(DraftSection section, ReadinessFix fix) {
		Object before = section.valueOf(fix.getBefore());
		Object after = fix.getAfter() == null ? null : section.valueOf(fix.getAfter());
		return new SectionChange(section, section.getFieldName(), section.getLabel(), before, after,
				fix.getAfter() != null && !Objects.equals(before, after));
	}
}
