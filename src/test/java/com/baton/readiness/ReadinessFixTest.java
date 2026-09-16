package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.common.BusinessException;
import com.baton.readiness.ReadinessFixService.Excerpt;
import com.baton.readiness.ReadinessFixService.GeneratedResult;
import com.baton.readiness.dto.GeneratedFix;
import com.baton.readiness.dto.GeneratedFixQuestion;

class ReadinessFixTest {

	private static final UUID SOURCE_ID = UUID.randomUUID();
	private static final HandoverDraftContent CURRENT = new HandoverDraftContent(
			"목적", null, List.of(), List.of(), List.of("결제 오류 시 고객센터와 협업"),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

	/** applyGeneration은 외부 의존성을 쓰지 않는다. */
	private final ReadinessFixService service = new ReadinessFixService(null, null, null, null, null, null, null, null);

	@Test
	void proposalKeepsOnlyTargetSectionAndCitedEvidence() {
		ReadinessFix fix = newFix();
		HandoverDraftContent patch = new HandoverDraftContent(
				"AI가 몰래 바꾼 목적", null, null, null,
				List.of("결제 오류 시 고객센터와 협업", "환불 오류는 CS팀이 당일 처리"),
				null, null, null, null, null, null);
		List<Excerpt> excerpts = List.of(
				new Excerpt(1, SOURCE_ID, "고객센터 매뉴얼.pdf", "청크 2/5", "환불 오류는 CS팀이 당일 처리"),
				new Excerpt(2, UUID.randomUUID(), "기타.pdf", null, "무관"));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(
				new GeneratedFix(true, patch, "환불 오류 처리 담당을 추가했어요", List.of(1, 1, 9), List.of()), excerpts));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.PROPOSED);
		assertThat(fix.getAfter().purpose()).isNull();
		assertThat(fix.getAfter().rulesAndExceptions()).hasSize(2);
		assertThat(fix.getBefore().rulesAndExceptions()).containsExactly("결제 오류 시 고객센터와 협업");
		assertThat(fix.getEvidence()).containsExactly(new ReadinessEvidence(SOURCE_ID, "고객센터 매뉴얼.pdf", "청크 2/5"));
	}

	@Test
	void unchangedProposalBecomesQuestions() {
		ReadinessFix fix = newFix();
		HandoverDraftContent samePatch = DraftSection.RULES_AND_EXCEPTIONS.only(CURRENT);

		service.applyGeneration(fix, CURRENT, new GeneratedResult(
				new GeneratedFix(true, samePatch, "", List.of(), List.of()), List.of()));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.NEEDS_INPUT);
		assertThat(fix.getQuestions()).hasSize(1);
		assertThat(fix.getQuestions().get(0).id()).isEqualTo("q1");
	}

	@Test
	void unresolvableAddsNewQuestionsOnlyOnceAndKeepsAnswers() {
		ReadinessFix fix = newFix();
		GeneratedFix unresolvable = new GeneratedFix(false, null, "", List.of(), List.of(
				new GeneratedFixQuestion("환불 오류 담당자는 누구인가요?", "담당자가 없어요"),
				new GeneratedFixQuestion("환불 오류 담당자는 누구인가요?", "중복"),
				new GeneratedFixQuestion(" ", "빈 질문")));

		service.applyGeneration(fix, CURRENT, new GeneratedResult(unresolvable, List.of()));
		assertThat(fix.getQuestions()).extracting(FixQuestion::id).containsExactly("q1");

		fix.answer("q1", "CS팀 박지민 매니저");
		service.applyGeneration(fix, CURRENT, new GeneratedResult(unresolvable, List.of()));

		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.NEEDS_INPUT);
		assertThat(fix.getQuestions()).hasSize(2);
		assertThat(fix.getQuestions().get(0).answer()).isEqualTo("CS팀 박지민 매니저");
		assertThat(fix.getQuestions().get(1).id()).isEqualTo("q2");
		assertThat(fix.answeredQuestions()).hasSize(1);
	}

	@Test
	void rejectsUnknownQuestionAndInvalidTransitions() {
		ReadinessFix fix = newFix();

		assertThatThrownBy(() -> fix.answer("q9", "답")).isInstanceOf(BusinessException.class);
		assertThatThrownBy(() -> fix.markApplied(2)).isInstanceOf(BusinessException.class);

		fix.discard();
		assertThat(fix.getStatus()).isEqualTo(ReadinessFixStatus.DISCARDED);
		assertThatThrownBy(fix::discard).isInstanceOf(BusinessException.class);
	}

	@Test
	void detectsStaleRevision() {
		ReadinessFix fix = newFix();

		assertThat(fix.isStaleAgainst(1)).isFalse();
		assertThat(fix.isStaleAgainst(2)).isTrue();
	}

	private static ReadinessFix newFix() {
		return ReadinessFix.open(UUID.randomUUID(), UUID.randomUUID(), ReadinessArea.EXCEPTION,
				DraftSection.RULES_AND_EXCEPTIONS, 1, CURRENT);
	}
}
