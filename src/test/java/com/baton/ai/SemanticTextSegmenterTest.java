package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SemanticTextSegmenterTest {

	@Test
	void separatesHeadingsTablesAndProceduresWithoutBreakingTheirRows() {
		String text = """
				## 쿠폰 정책
				일반 설명입니다.
				| 구분 | 담당자 |
				| 오류 | 김민성 |
				1. 쿠폰 기간을 확인한다.
				2. 최소 주문 금액을 확인한다.

				## 환불 정책
				환불은 팀장 승인을 받는다.
				""";

		List<SemanticTextSegmenter.Segment> segments = SemanticTextSegmenter.split(text, List.of());

		assertThat(segments).extracting(SemanticTextSegmenter.Segment::blockType)
				.containsExactly("TEXT", "TABLE", "PROCEDURE", "TEXT");
		assertThat(text.substring(segments.get(1).start(), segments.get(1).end()))
				.contains("| 구분 | 담당자 |", "| 오류 | 김민성 |");
		assertThat(text.substring(segments.get(2).start(), segments.get(2).end()))
				.contains("1. 쿠폰 기간", "2. 최소 주문 금액");
		assertThat(segments.get(0).heading()).isEqualTo("## 쿠폰 정책");
		assertThat(segments.get(3).heading()).isEqualTo("## 환불 정책");
	}

	@Test
	void neverCrossesPdfPageBoundary() {
		String text = "첫 페이지 설명\n\n두 번째 페이지 설명";
		int secondPage = text.indexOf("두 번째");

		List<SemanticTextSegmenter.Segment> segments = SemanticTextSegmenter.split(text, List.of(secondPage));

		assertThat(segments).hasSize(2);
		assertThat(segments.get(0).end()).isLessThanOrEqualTo(secondPage);
		assertThat(segments.get(1).start()).isEqualTo(secondPage);
	}
}
