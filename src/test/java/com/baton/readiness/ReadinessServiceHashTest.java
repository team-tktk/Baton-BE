package com.baton.readiness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.baton.ai.dto.HandoverDraftContent;
import com.fasterxml.jackson.databind.ObjectMapper;

class ReadinessServiceHashTest {

	private final ReadinessService service = new ReadinessService(null, null, null, null, null, new ObjectMapper(), null, null);

	private static HandoverDraftContent content(String purpose) {
		return new HandoverDraftContent(purpose, "완료", List.of(), List.of(), List.of("규칙"),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}

	@Test
	void sameContentAndRubricGiveSameHash() {
		assertThat(service.contentHash("v1", content("목적"), List.of()))
				.isEqualTo(service.contentHash("v1", content("목적"), List.of()))
				.hasSize(64);
	}

	@Test
	void contentOrRubricChangeGivesDifferentHash() {
		String base = service.contentHash("v1", content("목적"), List.of());

		assertThat(service.contentHash("v1", content("바뀐 목적"), List.of())).isNotEqualTo(base);
		assertThat(service.contentHash("v2", content("목적"), List.of())).isNotEqualTo(base);
	}

	@Test
	void describesMissingSources() {
		assertThat(ReadinessService.documentsText(List.of())).isEqualTo("(업로드 자료 없음)");
	}
}
