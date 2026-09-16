package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baton.masking.MaskingDetector.DetectedCandidate;

class MaskingDetectorTest {

	// 테스트용 가짜 번호. 검증번호만 규칙에 맞춘 값이다.
	private static final String VALID_RRN = "900101-1234568";
	private static final String INVALID_RRN = "900101-1234567";
	private static final String VALID_BUSINESS_NO = "220-81-62517";
	private static final String INVALID_BUSINESS_NO = "220-81-62518";
	private static final String VALID_CARD = "4111-1111-1111-1111";

	private final MaskingDetector detector = new MaskingDetector();

	@Test
	void findsContactsAndAccountInContract() {
		String text = """
				제2조 (연락처)
				담당자 이메일 : minji.kim@example.com
				담당자 전화번호 : 010-1234-5678
				제3조 (대금 지급)
				은행명 : ○○은행
				계좌번호 : 110-123-456789
				2024년 5월 20일
				""";

		List<DetectedCandidate> result = detector.detect(text);

		assertThat(result).extracting(DetectedCandidate::type)
				.containsExactly(MaskingType.EMAIL, MaskingType.PHONE, MaskingType.ACCOUNT);
		assertThat(result).noneMatch(DetectedCandidate::needsReview);
	}

	@Test
	void offsetsPointToExactMatchedText() {
		String text = "문의: 010-9876-5432 로 연락";

		DetectedCandidate phone = detector.detect(text).get(0);

		assertThat(text.substring(phone.startOffset(), phone.endOffset())).isEqualTo("010-9876-5432");
	}

	@Test
	void ignoresDatesAmountsAndShortNumbers() {
		String text = "계약일 2024-05-20, 금액 1,500,000원, 제3조 2항, 버전 1.2.3, 문서번호 2024-001";

		assertThat(detector.detect(text)).isEmpty();
	}

	@Test
	void emptyTextReturnsNoCandidates() {
		assertThat(detector.detect(null)).isEmpty();
		assertThat(detector.detect("   ")).isEmpty();
	}

	@Test
	void landlineWithHyphensIsAutoMasked() {
		DetectedCandidate phone = detector.detect("대표번호 070-1234-9876").get(0);

		assertThat(phone.type()).isEqualTo(MaskingType.PHONE);
		assertThat(phone.needsReview()).isFalse();
	}

	@Test
	void residentNumberWithValidChecksumIsAutoMasked() {
		DetectedCandidate rrn = detector.detect("주민번호 " + VALID_RRN).get(0);

		assertThat(rrn.type()).isEqualTo(MaskingType.RRN);
		assertThat(rrn.needsReview()).isFalse();
	}

	@Test
	void residentNumberWithInvalidChecksumNeedsReview() {
		DetectedCandidate rrn = detector.detect("주민번호 " + INVALID_RRN).get(0);

		assertThat(rrn.type()).isEqualTo(MaskingType.RRN);
		assertThat(rrn.needsReview()).isTrue();
	}

	@Test
	void residentNumberShapeWithImpossibleDateIsIgnoredAsResidentNumber() {
		List<DetectedCandidate> result = detector.detect("코드 901301-1234567");

		assertThat(result).noneMatch(c -> c.type() == MaskingType.RRN);
	}

	@Test
	void businessNumberChecksumDecidesReview() {
		DetectedCandidate valid = detector.detect("사업자등록번호 " + VALID_BUSINESS_NO).get(0);
		DetectedCandidate invalid = detector.detect("번호 " + INVALID_BUSINESS_NO).get(0);

		assertThat(valid.type()).isEqualTo(MaskingType.BUSINESS_NO);
		assertThat(valid.needsReview()).isFalse();
		assertThat(invalid.type()).isEqualTo(MaskingType.BUSINESS_NO);
		assertThat(invalid.needsReview()).isTrue();
	}

	@Test
	void unhyphenatedTenDigitsNeedBusinessKeyword() {
		assertThat(detector.detect("주문번호 2208162517")).noneMatch(c -> c.type() == MaskingType.BUSINESS_NO);
		assertThat(detector.detect("사업자번호: 2208162517"))
				.extracting(DetectedCandidate::type)
				.containsExactly(MaskingType.BUSINESS_NO);
	}

	@Test
	void cardNumberPassingLuhnIsDetectedAsCard() {
		DetectedCandidate card = detector.detect("결제카드 " + VALID_CARD).get(0);

		assertThat(card.type()).isEqualTo(MaskingType.CARD);
		assertThat(card.needsReview()).isFalse();
	}

	@Test
	void accountWithoutKeywordNeedsReview() {
		DetectedCandidate account = detector.detect("참고 번호 110-123-456789").get(0);

		assertThat(account.type()).isEqualTo(MaskingType.ACCOUNT);
		assertThat(account.needsReview()).isTrue();
	}

	@Test
	void keywordOnPreviousLineDoesNotApplyToNextLine() {
		List<DetectedCandidate> result = detector.detect("계좌번호 : 110-123-456789\n예비 번호 : 3333-01-5555111");

		assertThat(result).extracting(DetectedCandidate::needsReview).containsExactly(false, true);
	}

	@Test
	void numberInsideLongerSequenceIsNotSplitIntoPhone() {
		assertThat(detector.detect("일련번호 9901012345678901234"))
				.noneMatch(c -> c.type() == MaskingType.PHONE);
	}

	@Test
	void overlappingRulesKeepOnlyOneCandidate() {
		List<DetectedCandidate> result = detector.detect("연락처 01012345678");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).type()).isEqualTo(MaskingType.PHONE);
	}
}
