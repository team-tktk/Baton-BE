package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MaskingApplierTest {

	private static final UUID SOURCE_ID = UUID.randomUUID();
	private static final UUID HANDOVER_ID = UUID.randomUUID();

	@Test
	void replacesAppliedSpansWithNumberedTokens() {
		String text = "메일 a@x.com, 다시 A@x.com / 010-1111-2222 / b@y.com";

		String masked = MaskingApplier.apply(text, List.of(
				span(text, "a@x.com", MaskingType.EMAIL),
				span(text, "A@x.com", MaskingType.EMAIL),
				span(text, "010-1111-2222", MaskingType.PHONE),
				span(text, "b@y.com", MaskingType.EMAIL)));

		assertThat(masked).isEqualTo("메일 [이메일#1], 다시 [이메일#1] / [전화번호#1] / [이메일#2]");
	}

	@Test
	void releasedCandidatesStayAsIs() {
		String text = "참고 110-123-456789 / 담당 김민지";
		MaskingCandidate account = span(text, "110-123-456789", MaskingType.ACCOUNT);
		account.decide(false);

		String masked = MaskingApplier.apply(text, List.of(account, span(text, "김민지", MaskingType.CUSTOM)));

		assertThat(masked).isEqualTo("참고 110-123-456789 / 담당 [비공개#1]");
	}

	@Test
	void sameNumberDespiteDifferentFormatting() {
		String text = "010-1111-2222 그리고 01011112222";

		String masked = MaskingApplier.apply(text, List.of(
				span(text, "01011112222", MaskingType.PHONE),
				span(text, "010-1111-2222", MaskingType.PHONE)));

		assertThat(masked).isEqualTo("[전화번호#1] 그리고 [전화번호#1]");
	}

	@Test
	void noCandidatesKeepsTextUnchanged() {
		assertThat(MaskingApplier.apply("그대로\n유지", List.of())).isEqualTo("그대로\n유지");
	}

	@Test
	void tokensAtBothEndsOfText() {
		String text = "a@x.com 중간 010-1111-2222";

		String masked = MaskingApplier.apply(text, List.of(
				span(text, "a@x.com", MaskingType.EMAIL),
				span(text, "010-1111-2222", MaskingType.PHONE)));

		assertThat(masked).isEqualTo("[이메일#1] 중간 [전화번호#1]");
	}

	private static MaskingCandidate span(String text, String value, MaskingType type) {
		int start = text.indexOf(value);
		return MaskingCandidate.manual(SOURCE_ID, HANDOVER_ID, type, start, start + value.length(), text);
	}
}
