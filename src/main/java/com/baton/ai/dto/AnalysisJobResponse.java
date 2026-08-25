package com.baton.ai.dto;

import java.time.Instant;
import java.util.UUID;

import com.baton.ai.AnalysisJob;

public record AnalysisJobResponse(
		UUID jobId,
		String status,
		int progress,
		String currentStep,
		String error,
		Instant updatedAt) {

	public static AnalysisJobResponse from(AnalysisJob job) {
		return new AnalysisJobResponse(
				job.getId(),
				job.getStatus().name(),
				job.getProgress(),
				job.getCurrentStep(),
				job.getError(),
				job.getUpdatedAt());
	}
}
