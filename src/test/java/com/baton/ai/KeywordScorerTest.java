package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeywordScorerTest {
	@Test
	void 한국어_조사가_붙은_질문도_핵심_키워드를_찾는다() {
		double relevant = KeywordScorer.score("쿠폰이 적용되지 않으면 누구에게 알려?", "쿠폰 적용 오류는 운영팀 담당자에게 공유한다.");
		double irrelevant = KeywordScorer.score("쿠폰이 적용되지 않으면 누구에게 알려?", "월말 정산 파일을 보관한다.");

		assertThat(relevant).isGreaterThan(irrelevant);
		assertThat(relevant).isGreaterThan(0.08);
	}

	@Test
	void 관련없는_문장은_점수가_없다() {
		assertThat(KeywordScorer.score("환불 담당자", "서버 배포 절차와 점검 항목")).isZero();
	}
}
