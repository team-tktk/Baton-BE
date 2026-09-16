package com.baton.readiness.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.baton.ai.DraftSection;
import com.baton.readiness.FixQuestion;
import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessEvidence;
import com.baton.readiness.ReadinessFix;
import com.baton.readiness.ReadinessFixStatus;

/**
 * 부족 항목 보완안.
 *
 * @param before       수정 전 섹션 값(섹션에 따라 문자열 또는 배열). 문서 조회 응답의 content.{sectionField}와 같은 형식.
 * @param after        수정 후 섹션 값. 추가 질문 답변 대기(NEEDS_INPUT) 중이면 null.
 * @param baseRevision 보완안을 만들 때의 문서 버전. 적용 요청에 그대로 보낸다.
 * @param stale        그사이 문서가 바뀌어 이 보완안을 적용할 수 없으면 true — 새 보완안을 만들어야 한다.
 */
public record ReadinessFixResponse(
		UUID fixId,
		ReadinessArea area,
		String areaLabel,
		DraftSection section,
		String sectionLabel,
		String sectionField,
		ReadinessFixStatus status,
		long baseRevision,
		boolean stale,
		Long appliedRevision,
		Object before,
		Object after,
		String changeSummary,
		List<FixQuestion> questions,
		List<ReadinessEvidence> evidence,
		Instant createdAt,
		Instant updatedAt) {

	public static ReadinessFixResponse of(ReadinessFix fix, long currentRevision) {
		DraftSection section = fix.getSection();
		return new ReadinessFixResponse(
				fix.getId(),
				fix.getArea(),
				fix.getArea().getLabel(),
				section,
				section.getLabel(),
				section.getFieldName(),
				fix.getStatus(),
				fix.getBaseRevision(),
				fix.getStatus().isOpen() && fix.isStaleAgainst(currentRevision),
				fix.getAppliedRevision(),
				section.valueOf(fix.getBefore()),
				section.valueOf(fix.getAfter()),
				fix.getChangeSummary(),
				fix.getQuestions(),
				fix.getEvidence(),
				fix.getCreatedAt(),
				fix.getUpdatedAt());
	}
}
