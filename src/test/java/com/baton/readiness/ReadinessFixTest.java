package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.ClarificationQuestion;
import com.baton.ai.ClarificationQuestionType;
import com.baton.ai.DraftSection;
import com.baton.ai.dto.ConfirmedCriterion;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.ai.dto.QuestionOption;
import com.baton.ai.dto.TaskItem;
import com.baton.common.BusinessException;
import com.baton.readiness.ReadinessFixService.Excerpt;
import com.baton.readiness.ReadinessFixService.GeneratedResult;
import com.baton.readiness.dto.GeneratedAreaFix;
import com.baton.readiness.dto.GeneratedFix;
import com.baton.readiness.dto.GeneratedItemQuestion;
import com.baton.readiness.dto.ReadinessFixResponse;

class ReadinessFixTest {

	private static final UUID SOURCE_ID = UUID.randomUUID();
	private static final TaskItem COUPON = new TaskItem("할인 코드 설정", "매주 반복", "운영툴에서 할인 코드 생성", "월요일 오전 설정", "매주 월요일");
	private static final HandoverDraftContent CURRENT = new HandoverDraftContent(
			"목적", null, List.of(), List.of(COUPON), List.of("결제 오류 시 고객센터와 협업"),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

	private static final ReadinessItem EXCEPTION_CONFLICT = item(ReadinessArea.EXCEPTION, ReadinessStatus.CONFLICT,
			List.of(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA),
			List.of(new ItemQuestion("환불 승인은 누가 하나요?", "자료마다 달라요", List.of("A.pdf: 팀장", "B.xlsx: 마케팅 후 팀장"))));
	private static final ReadinessItem PROCEDURE_PARTIAL = item(ReadinessArea.PROCEDURE, ReadinessStatus.PARTIAL,
			List.of(DraftSection.RECURRING_TASKS), List.of());

	/** applyGeneration은 외부 의존성을 쓰지 않는다. */
	private final ReadinessFixService service = new ReadinessFixService(null, null, null, null, null, null, null, null, null);

	@Test
	void openCollectsEvaluationAndDeferredQuestionsPerArea() {
		ClarificationQuestion deferred = ClarificationQuestion.create(UUID.randomUUID(), ClarificationQuestionType.CONFLICT,
				"환불 승인 기준은 무엇인가요?", "자료가 달라요", null,
				List.of(new QuestionOption("팀장 승인", null)), List.of(DraftSection.RULES_AND_EXCEPTIONS), 1);
		ClarificationQuestion otherArea = ClarificationQuestion.create(UUID.randomUUID(), ClarificationQuestionType.INTERVIEW,
				"운영툴 권한은?", null, null, List.of(), List.of(DraftSection.ACCESS_ACCOUNTS), 2);

		List<FixQuestion> questions = ReadinessFixService.initialQuestions(
				List.of(EXCEPTION_CONFLICT, PROCEDURE_PARTIAL), List.of(deferred, otherArea));
		ReadinessFix fix = ReadinessFix.open(UUID.randomUUID(), UUID.randomUUID(),
				List.of(EXCEPTION_CONFLICT, PROCEDURE_PARTIAL), questions, 1, CURRENT);

		assertThat(fix.getQuestions()).extracting(FixQuestion::id, FixQuestion::area, FixQuestion::question)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("q1", ReadinessArea.EXCEPTION, "환불 승인은 누가 하나요?"),
						org.assertj.core.groups.Tuple.tuple("q2", ReadinessArea.EXCEPTION, "환불 승인 기준은 무엇인가요?"));
		assertThat(fix.getQuestions().get(1).options()).containsExactly("팀장 승인");
		assertThat(fix.getSections()).containsExactly(
				DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA, DraftSection.RECURRING_TASKS);
		assertThat(fix.getBefore().recurringTasks()).containsExactly(COUPON);
		assertThat(fix.getBefore().purpose()).isNull();
		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.NEEDS_INPUT);
	}

	@Test
	void proposesOnlyAreasWhoseSectionsChangedAndKeepsOtherSections() {
		ReadinessFix fix = newFix();
		TaskItem detailed = new TaskItem("할인 코드 설정", "매주 반복",
				"1) 운영툴 로그인 2) 쿠폰 메뉴에서 코드 생성 3) 마케팅팀에 공유", "월요일 오전 설정", "매주 월요일");
		HandoverDraftContent patch = new HandoverDraftContent(
				"AI가 몰래 바꾼 목적", null, null, List.of(detailed), null, null, null, null, null, null, null);
		List<Excerpt> excerpts = List.of(
				new Excerpt(1, SOURCE_ID, "운영 매뉴얼.pdf", "청크 2/5", "쿠폰 생성 절차"),
				new Excerpt(2, UUID.randomUUID(), "기타.pdf", null, "무관"));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(new GeneratedFix(patch, List.of(
				new GeneratedAreaFix(ReadinessArea.PROCEDURE, true, "recurringTasks에 단계별 절차를 추가했어요", List.of(1, 1, 9), List.of()),
				new GeneratedAreaFix(ReadinessArea.EXCEPTION, false, "", List.of(), List.of()))), excerpts));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.PROPOSED);
		assertThat(fix.proposedSections()).containsExactly(DraftSection.RECURRING_TASKS);
		assertThat(fix.getAfter().recurringTasks()).containsExactly(detailed);
		assertThat(fix.getAfter().rulesAndExceptions()).containsExactly("결제 오류 시 고객센터와 협업");
		assertThat(fix.getAfter().purpose()).isNull();
		FixAreaResult procedure = fix.areaResult(ReadinessArea.PROCEDURE).orElseThrow();
		assertThat(procedure.changeSummary()).isEqualTo("반복 업무에 단계별 절차를 추가했어요");
		assertThat(procedure.evidence()).containsExactly(new ReadinessEvidence(SOURCE_ID, "운영 매뉴얼.pdf", "청크 2/5"));
		assertThat(fix.areaResult(ReadinessArea.EXCEPTION).orElseThrow().proposed()).isFalse();
	}

	@Test
	void conflictWithoutAnswerIsNotProposedEvenIfAiPickedAValue() {
		ReadinessFix fix = newFix();
		HandoverDraftContent patch = new HandoverDraftContent(null, null, null, null,
				List.of("결제 오류 시 고객센터와 협업", "환불은 팀장이 승인한다."), null, null, null, null, null,
				List.of(new ConfirmedCriterion("환불 승인", "팀장 승인")));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(new GeneratedFix(patch, List.of(
				new GeneratedAreaFix(ReadinessArea.EXCEPTION, true, "승인 기준을 적었어요", List.of(), List.of()))), List.of()));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.NEEDS_INPUT);
		assertThat(fix.getAfter()).isNull();
		assertThat(fix.areaResult(ReadinessArea.EXCEPTION).orElseThrow().proposed()).isFalse();
		// 아직 답하지 않은 질문(q1)이 있으니 새 질문을 붙이지 않는다. 절차 영역은 물을 게 없어 기본 질문을 붙인다.
		assertThat(fix.getQuestions()).filteredOn(question -> question.area() == ReadinessArea.EXCEPTION).hasSize(1);
		assertThat(fix.getQuestions()).filteredOn(question -> question.area() == ReadinessArea.PROCEDURE).hasSize(1);
	}

	@Test
	void conflictWithAnswerWritesConfirmedCriteriaAcrossSections() {
		ReadinessFix fix = newFix();
		fix.answer("q1", "B.xlsx: 마케팅 후 팀장");
		HandoverDraftContent patch = new HandoverDraftContent(null, null, null, null,
				List.of("결제 오류 시 고객센터와 협업", "환불은 마케팅 확인 후 팀장이 승인한다."), null, null, null, null, null,
				List.of(new ConfirmedCriterion("환불 승인", "마케팅 확인 후 팀장 승인")));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(new GeneratedFix(patch, List.of(
				new GeneratedAreaFix(ReadinessArea.EXCEPTION, true, "승인 기준을 확정했어요", List.of(), List.of()))), List.of()));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.PROPOSED);
		assertThat(fix.proposedSections())
				.containsExactly(DraftSection.RULES_AND_EXCEPTIONS, DraftSection.CONFIRMED_CRITERIA);
		assertThat(fix.getAfter().confirmedCriteria()).hasSize(1);
		assertThat(ReadinessFixService.affectedAreas(fix, fix.proposedSections()))
				.containsExactlyInAnyOrder(ReadinessArea.EXCEPTION, ReadinessArea.CONTACTS, ReadinessArea.EVIDENCE);

		ReadinessFixResponse response = ReadinessFixResponse.of(fix, 1);
		assertThat(response.sections()).extracting(ReadinessFixResponse.SectionChange::changed)
				.containsExactly(true, true, false);
		assertThat(response.unansweredCount()).isEqualTo(1);
	}

	@Test
	void unresolvedAreaGetsNewQuestionsOnceAndKeepsAnswers() {
		ReadinessFix fix = newFix();
		GeneratedFix unresolved = new GeneratedFix(null, List.of(new GeneratedAreaFix(ReadinessArea.PROCEDURE, false, "",
				List.of(), List.of(
						new GeneratedItemQuestion("쿠폰 생성 뒤 누구에게 공유하나요?", "자료에 없어요", List.of()),
						new GeneratedItemQuestion("쿠폰 생성 뒤 누구에게 공유하나요?", "중복", List.of()),
						new GeneratedItemQuestion(" ", "빈 질문", List.of())))));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(unresolved, List.of()));
		List<FixQuestion> procedure = fix.getQuestions().stream()
				.filter(question -> question.area() == ReadinessArea.PROCEDURE).toList();
		assertThat(procedure).extracting(FixQuestion::id).containsExactly("q2");

		fix.answer("q2", "마케팅팀");
		service.applyGeneration(fix, CURRENT, new GeneratedResult(unresolved, List.of()));

		assertThat(fix.getQuestions()).filteredOn(question -> question.area() == ReadinessArea.PROCEDURE)
				.extracting(FixQuestion::id).containsExactly("q2", "q3");
		assertThat(fix.getQuestions().get(1).answer()).isEqualTo("마케팅팀");
	}

	@Test
	void rejectsUnknownQuestionAndInvalidTransitions() {
		ReadinessFix fix = newFix();

		assertThatThrownBy(() -> fix.answer("q9", "답")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fix.markApplied(2)).isInstanceOf(BusinessException.class);

		fix.discard();
		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.DISCARDED);
		assertThatThrownBy(fix::discard).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fix.answer("q1", "답")).isInstanceOf(BusinessException.class);
	}

	@Test
	void detectsStaleRevision() {
		ReadinessFix fix = newFix();

		assertThat(fix.isStaleAgainst(1)).isFalse();
		assertThat(fix.isStaleAgainst(2)).isTrue();
	}

	private static ReadinessFix newFix() {
		List<ReadinessItem> items = List.of(EXCEPTION_CONFLICT, PROCEDURE_PARTIAL);
		return ReadinessFix.open(UUID.randomUUID(), UUID.randomUUID(), items,
				ReadinessFixService.initialQuestions(items, List.of()), 1, CURRENT);
	}

	private static ReadinessItem item(ReadinessArea area, ReadinessStatus status, List<DraftSection> sections,
			List<ItemQuestion> questions) {
		return new ReadinessItem(area, status, sections.get(0), null, "요약", "해결 방법", List.of(), sections, questions);
	}
}
