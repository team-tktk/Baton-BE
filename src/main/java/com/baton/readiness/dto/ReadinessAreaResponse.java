package com.baton.readiness.dto;

import java.util.List;

import com.baton.ai.DraftSection;
import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessEvidence;
import com.baton.readiness.ReadinessItem;
import com.baton.readiness.ReadinessRubric;
import com.baton.readiness.ReadinessStatus;

/**
 * 영역 하나의 평가 결과.
 *
 * @param percent  영역 달성률(0~100). 평가 기준 버전의 상태별 비율.
 * @param weight   이 영역의 배점(총점 100 중).
 * @param keyIssue "중요한 확인"에 들어가는 항목인지.
 * @param section  부족한 내용이 들어갈 문서 섹션. 보완안 적용 위치이자 "문서에서 수정하기" 이동 위치.
 */
public record ReadinessAreaResponse(
		ReadinessArea area,
		String label,
		String criteria,
		int weight,
		ReadinessStatus status,
		String statusLabel,
		int percent,
		boolean keyIssue,
		DraftSection section,
		String sectionLabel,
		String anchorText,
		String summary,
		String resolution,
		List<ReadinessEvidence> evidence) {

	public static ReadinessAreaResponse of(ReadinessItem item, ReadinessRubric rubric, boolean keyIssue) {
		return new ReadinessAreaResponse(
				item.area(),
				item.area().getLabel(),
				rubric.criteria().get(item.area()),
				rubric.weight(item.area()),
				item.status(),
				item.status().getLabel(),
				rubric.areaPercent(item.status()),
				keyIssue,
				item.section(),
				item.section().getLabel(),
				item.anchorText(),
				item.summary(),
				item.resolution(),
				item.evidence() == null ? List.of() : item.evidence());
	}
}
