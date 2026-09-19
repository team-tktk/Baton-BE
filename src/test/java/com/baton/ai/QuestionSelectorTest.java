package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baton.ai.dto.GeneratedQuestion;

class QuestionSelectorTest {

	@Test
	void 중요도순으로_정렬한다() {
		List<GeneratedQuestion> result = QuestionSelector.dedupe(List.of(
				question("반품 승인은 누가 하나요?", 3),
				question("정산 마감일은 언제인가요?", 1),
				question("운영 어드민 계정은 어떻게 받나요?", 2)), List.of());

		assertThat(result).extracting(GeneratedQuestion::questionText).containsExactly(
				"정산 마감일은 언제인가요?", "운영 어드민 계정은 어떻게 받나요?", "반품 승인은 누가 하나요?");
	}

	@Test
	void 표현만_다른_같은_뜻의_질문은_중요한_것_하나만_남긴다() {
		List<GeneratedQuestion> result = QuestionSelector.dedupe(List.of(
				question("쿠폰 승인은 누가 하나요?", 2),
				question("쿠폰 승인은 누가 하나요", 1),
				question("쿠폰 승인, 누가 하나요?", 3)), List.of());

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().priority()).isEqualTo(1);
	}

	@Test
	void 이미_물어본_질문과_같은_뜻이면_뺀다() {
		List<GeneratedQuestion> result = QuestionSelector.dedupe(List.of(
				question("정산 마감일은 언제인가요?", 1),
				question("배송업체 담당자 연락처가 있나요?", 2)),
				List.of("정산 마감일이 언제인가요?"));

		assertThat(result).extracting(GeneratedQuestion::questionText).containsExactly("배송업체 담당자 연락처가 있나요?");
	}

	@Test
	void 다른_대상을_묻는_질문은_남긴다() {
		List<GeneratedQuestion> result = QuestionSelector.dedupe(List.of(
				question("쿠폰 승인은 누가 하나요?", 1),
				question("반품 승인은 누가 하나요?", 2)), List.of());

		assertThat(result).hasSize(2);
	}

	@Test
	void 빈_질문은_버린다() {
		assertThat(QuestionSelector.dedupe(List.of(question(" ", 1), question(null, 2)), List.of())).isEmpty();
	}

	private GeneratedQuestion question(String text, Integer priority) {
		return new GeneratedQuestion(ClarificationQuestionType.INTERVIEW, text, "이유", null, List.of(), List.of(), priority);
	}
}
