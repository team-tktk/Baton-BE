package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceChunkerHeadingTest {
	@Test
	void 현재_문단의_가장_가까운_섹션_제목을_찾는다() {
		String text = "# 개요\n업무 설명\n\n## 장애 대응\n쿠폰 오류 처리 절차";

		assertThat(EvidenceChunker.headingAt(text, text.indexOf("쿠폰"))).isEqualTo("## 장애 대응");
	}

	@Test
	void 번호가_붙은_문서_제목도_인식한다() {
		String text = "3.2 주요 확인 사항\n쿠폰 활성 기간 확인";

		assertThat(EvidenceChunker.headingAt(text, text.indexOf("쿠폰"))).isEqualTo("3.2 주요 확인 사항");
	}
}
