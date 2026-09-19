package com.baton.readiness.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baton.readiness.ReadinessArea;
import com.baton.readiness.ReadinessEvaluation;
import com.baton.readiness.ReadinessGrade;
import com.baton.readiness.ReadinessItem;
import com.baton.readiness.ReadinessRubric;
import com.baton.readiness.ReadinessStatus;

/**
 * 인수인계 준비도.
 *
 * @param stale          평가 이후 문서나 업로드 자료가 바뀌었으면 true — POST /readiness/evaluate로 다시 평가한다.
 * @param draftRevision  평가한 문서 버전.
 * @param areas          잃은 점수가 큰 영역부터 정렬.
 * @param deferredQuestionCount "나중에 답하기"로 미룬 질문 수(전 영역 합계). 점수에는 반영하지 않는다.
 */
public record ReadinessResponse(
		UUID evaluationId,
		String rubricVersion,
		int score,
		ReadinessGrade grade,
		String gradeLabel,
		int keyIssueCount,
		boolean stale,
		long draftRevision,
		Instant evaluatedAt,
		List<ReadinessAreaResponse> areas,
		int deferredQuestionCount) {

	public static ReadinessResponse of(ReadinessEvaluation evaluation, ReadinessRubric rubric, boolean stale,
			List<DeferredQuestionResponse> deferredQuestions) {
		Map<ReadinessArea, ReadinessStatus> statuses = evaluation.statuses();
		Set<ReadinessArea> keyIssues = Set.copyOf(rubric.keyIssues(statuses));
		Map<ReadinessArea, ReadinessItem> itemsByArea = evaluation.getItems().stream()
				.collect(Collectors.toMap(ReadinessItem::area, Function.identity(), (first, second) -> first));

		Map<ReadinessArea, List<DeferredQuestionResponse>> deferredByArea = deferredQuestions.stream()
				.collect(Collectors.groupingBy(DeferredQuestionResponse::area));

		List<ReadinessAreaResponse> areas = rubric.prioritized(statuses).stream()
				.filter(itemsByArea::containsKey)
				.map(area -> ReadinessAreaResponse.of(itemsByArea.get(area), rubric, keyIssues.contains(area),
						deferredByArea.getOrDefault(area, List.of())))
				.toList();
		ReadinessGrade grade = rubric.grade(evaluation.getScore());

		return new ReadinessResponse(
				evaluation.getId(),
				evaluation.getRubricVersion(),
				evaluation.getScore(),
				grade,
				grade.getLabel(),
				keyIssues.size(),
				stale,
				evaluation.getDraftRevision(),
				evaluation.getCreatedAt(),
				areas,
				deferredQuestions.size());
	}
}
