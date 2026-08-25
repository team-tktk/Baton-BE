package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record Citation(
		UUID sourceId,
		String title,
		String locator,
		Instant updatedAt) {
}
