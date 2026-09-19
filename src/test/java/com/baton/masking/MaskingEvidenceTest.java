package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.baton.ai.EvidenceLocator;
import com.baton.ai.PdfTextLocation;
import com.baton.ai.dto.EvidenceHighlight;

class MaskingEvidenceTest {
	@Test
	void replacementLengthChangesPreserveLaterPageAndRemoveSensitiveLineBox() {
		String raw = "private@example.com\nCheck coupon dates.";
		var first = new EvidenceHighlight(1, .1, .1, .4, .03);
		var second = new EvidenceHighlight(2, .1, .2, .4, .03);
		var locations = List.of(new PdfTextLocation(0, 19, 1, first),
				new PdfTextLocation(20, raw.length(), 2, second));
		var candidate = MaskingCandidate.manual(UUID.randomUUID(), UUID.randomUUID(), MaskingType.EMAIL, 0, 19, raw);
		var masked = MaskingApplier.applyWithLocations(raw, List.of(candidate), locations);
		assertThat(masked.text()).doesNotContain("private@example.com");
		assertThat(masked.locations().getFirst().highlight()).isNull();
		var evidence = EvidenceLocator.locate(masked.text(), "Check coupon dates.", masked.locations());
		assertThat(evidence.page()).isEqualTo(2);
		assertThat(evidence.highlights()).containsExactly(second);
	}

	@Test
	void manualMaskAcrossPagesDoesNotLeaveOriginalOffsetsOrCoordinates() {
		String raw = "first\nsecond\nlast";
		var candidate = MaskingCandidate.manual(UUID.randomUUID(), UUID.randomUUID(), MaskingType.CUSTOM, 2, 10, raw);
		var locations = List.of(new PdfTextLocation(0, 5, 1, new EvidenceHighlight(1, .1, .1, .3, .03)),
				new PdfTextLocation(6, 12, 2, new EvidenceHighlight(2, .1, .1, .3, .03)));
		var masked = MaskingApplier.applyWithLocations(raw, List.of(candidate), locations);
		assertThat(masked.locations()).allSatisfy(location -> {
			assertThat(location.highlight()).isNull();
			assertThat(location.start()).isBetween(0, masked.text().length());
			assertThat(location.end()).isBetween(location.start(), masked.text().length());
		});
	}
}
