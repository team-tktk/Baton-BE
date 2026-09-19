package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionType;
import com.baton.ai.DraftSection;
import com.baton.readiness.dto.DeferredQuestionResponse;
import com.baton.readiness.dto.ReadinessAreaResponse;
import com.baton.readiness.dto.ReadinessResponse;

/** 나중에 답하기로 미룬 질문은 준비도 영역에 붙여 보여주기만 하고 점수는 바꾸지 않는다. */
class ReadinessDeferredQuestionTest {

	private final UUID handoverId = UUID.randomUUID();
	private final ReadinessRubric rubric = ReadinessRubrics.CURRENT;

	@Test
	void everySectionMapsToAnArea() {
		Arrays.stream(DraftSection.values())
				.forEach(section -> assertThat(ReadinessArea.of(section).getSections()).contains(section));
	}

	@Test
	void sectionMapsToAreaThatUsesItAsPrimary() {
		assertThat(ReadinessArea.of(DraftSection.STAKEHOLDERS)).isEqualTo(ReadinessArea.CONTACTS);
		assertThat(ReadinessArea.of(DraftSection.RULES_AND_EXCEPTIONS)).isEqualTo(ReadinessArea.EXCEPTION);
		assertThat(ReadinessArea.of(DraftSection.RECURRING_TASKS)).isEqualTo(ReadinessArea.PROCEDURE);
		assertThat(ReadinessArea.of(DraftSection.CONFIRMED_CRITERIA)).isEqualTo(ReadinessArea.EXCEPTION);
	}

	@Test
	void questionAttachesToAreaOfFirstTargetSection() {
		assertThat(ReadinessService.areaOf(question(List.of(DraftSection.SCHEDULE, DraftSection.STAKEHOLDERS))))
				.isEqualTo(ReadinessArea.SCHEDULE);
		assertThat(ReadinessService.areaOf(question(List.of()))).isEqualTo(ReadinessArea.EXCEPTION);
	}

	@Test
	void deferredQuestionsAreShownWithoutChangingScore() {
		ReadinessEvaluation evaluation = ReadinessEvaluation.create(handoverId, rubric, "hash", 1, Arrays.stream(ReadinessArea.values())
				.map(area -> new ReadinessItem(area, ReadinessStatus.SUFFICIENT, area.primarySection(), null, "충분", null, List.of()))
				.toList());
		ClarificationQuestion deferred = question(List.of(DraftSection.STAKEHOLDERS));

		ReadinessResponse without = ReadinessResponse.of(evaluation, rubric, false, List.of());
		ReadinessResponse with = ReadinessResponse.of(evaluation, rubric, false,
				List.of(DeferredQuestionResponse.from(deferred, ReadinessService.areaOf(deferred))));

		assertThat(with.score()).isEqualTo(without.score()).isEqualTo(100);
		assertThat(with.deferredQuestionCount()).isEqualTo(1);
		assertThat(with.areas())
				.filteredOn(area -> area.area() == ReadinessArea.CONTACTS)
				.singleElement()
				.satisfies(area -> {
					assertThat(area.status()).isEqualTo(ReadinessStatus.SUFFICIENT);
					assertThat(area.deferredQuestions()).extracting(DeferredQuestionResponse::questionText)
							.containsExactly("환불 오류 담당자는 누구인가요?");
				});
		assertThat(with.areas())
				.filteredOn(area -> area.area() != ReadinessArea.CONTACTS)
				.allSatisfy(area -> assertThat(area.deferredQuestions()).isEmpty());
	}

	private ClarificationQuestion question(List<DraftSection> targetSections) {
		return ClarificationQuestion.create(handoverId, ClarificationQuestionType.INTERVIEW,
				"환불 오류 담당자는 누구인가요?", "환불 문의 대응이 막혀요", null, List.of(), targetSections, 1);
	}
}
