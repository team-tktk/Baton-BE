package com.baton.readiness.dto;

import java.util.List;
import java.util.Map;

import com.baton.ai.DraftSection;
import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessRubric;
import com.baton.readiness.ReadinessStatus;

/** 현재 평가 기준(점수 안내 툴팁용). */
public record ReadinessRubricResponse(
		String version,
		List<AreaCriterion> areas,
		Map<ReadinessStatus, Integer> statusPercent,
		int readyScore,
		int minimumScore,
		int keyIssueCount) {

	public record AreaCriterion(
			ReadinessArea area,
			String label,
			String criteria,
			int weight,
			List<DraftSection> sections) {
	}

	public static ReadinessRubricResponse from(ReadinessRubric rubric) {
		List<AreaCriterion> areas = List.of(ReadinessArea.values()).stream()
				.map(area -> new AreaCriterion(area, area.getLabel(), rubric.criteria().get(area),
						rubric.weight(area), area.getSections()))
				.toList();
		return new ReadinessRubricResponse(rubric.version(), areas, rubric.statusPercent(),
				rubric.readyScore(), rubric.minimumScore(), rubric.keyIssueCount());
	}
}
