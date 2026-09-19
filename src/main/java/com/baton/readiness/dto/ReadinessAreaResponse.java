package com.baton.readiness.dto;

import java.util.List;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.ClarificationQuestionResponse.TargetSection;
import com.baton.readiness.ItemQuestion;
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
 * @param section  "문서에서 수정하기" 이동 위치(targetSections의 첫 번째).
 * @param targetSections 보완안이 고칠 문서 섹션(해결 방법이 가리키는 곳, 1~3개).
 * @param questions 보완할 때 인계자에게 물을 질문(자료에 답이 없는 것만). 충분한 영역은 빈 배열.
 * @param deferredQuestions 이 영역에 붙은 "나중에 답하기" 질문. 표시용이고 점수·상태에는 영향이 없다.
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
		List<ReadinessEvidence> evidence,
		List<TargetSection> targetSections,
		List<ItemQuestion> questions,
		List<DeferredQuestionResponse> deferredQuestions) {

	public static ReadinessAreaResponse of(ReadinessItem item, ReadinessRubric rubric, boolean keyIssue,
			List<DeferredQuestionResponse> deferredQuestions) {
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
				item.evidence() == null ? List.of() : item.evidence(),
				item.fixSections().stream()
						.map(section -> new TargetSection(section, section.getFieldName(), section.getLabel()))
						.toList(),
				item.questionList(),
				deferredQuestions);
	}
}
