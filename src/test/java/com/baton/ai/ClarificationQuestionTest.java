package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClarificationQuestionTest {

	@Test
	void 모름_해당없음_나중에답하기를_구분해_저장한다() {
		ClarificationQuestion question = newQuestion();

		question.resolveWithoutAnswer(ClarificationQuestionStatus.UNKNOWN);
		assertThat(question.getStatus()).isEqualTo(ClarificationQuestionStatus.UNKNOWN);
		assertThat(question.needsApply()).isTrue();

		question.resolveWithoutAnswer(ClarificationQuestionStatus.NOT_APPLICABLE);
		assertThat(question.getStatus()).isEqualTo(ClarificationQuestionStatus.NOT_APPLICABLE);

		question.resolveWithoutAnswer(ClarificationQuestionStatus.DEFERRED);
		assertThat(question.getStatus()).isEqualTo(ClarificationQuestionStatus.DEFERRED);
		assertThat(question.needsApply()).isFalse();
	}

	@Test
	void 반영된_뒤_답이_바뀌면_다시_반영_대상이_된다() {
		ClarificationQuestion question = newQuestion();
		question.answer("팀장");
		question.markApplied(Instant.now());
		assertThat(question.needsApply()).isFalse();

		question.answer("팀장");
		assertThat(question.needsApply()).isFalse();

		question.answer("마케팅 확인 후 팀장");
		assertThat(question.needsApply()).isTrue();
	}

	@Test
	void 답변_없이_PENDING이나_ANSWERED로는_바꿀_수_없다() {
		ClarificationQuestion question = newQuestion();

		assertThatThrownBy(() -> question.resolveWithoutAnswer(ClarificationQuestionStatus.PENDING))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> question.resolveWithoutAnswer(ClarificationQuestionStatus.ANSWERED))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private ClarificationQuestion newQuestion() {
		return ClarificationQuestion.create(UUID.randomUUID(), ClarificationQuestionType.INTERVIEW,
				"쿠폰 승인은 누가 하나요?", "승인 라인이 자료에 없음", null, List.of(),
				List.of(DraftSection.RULES_AND_EXCEPTIONS), 1);
	}
}
