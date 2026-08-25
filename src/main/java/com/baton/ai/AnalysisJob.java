package com.baton.ai;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 파일 분석과 초안 생성을 HTTP 요청과 분리해서 추적하는 비동기 작업. */
@Entity
@Table(name = "analysis_jobs", indexes = {
		@Index(name = "idx_analysis_jobs_handover_created", columnList = "handover_id,created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AnalysisJobStatus status;

	@Column(nullable = false)
	private int progress;

	@Column(name = "current_step", nullable = false, length = 100)
	private String currentStep;

	@Lob
	@Column(name = "error_detail")
	private String error;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	private long version;

	private AnalysisJob(UUID handoverId) {
		this.handoverId = handoverId;
		this.status = AnalysisJobStatus.QUEUED;
		this.progress = 0;
		this.currentStep = "분석 대기 중";
	}

	public static AnalysisJob create(UUID handoverId) {
		return new AnalysisJob(handoverId);
	}

	public void updateProgress(AnalysisJobStatus status, int progress, String currentStep) {
		if (this.status.isTerminal()) {
			return;
		}
		this.status = status;
		this.progress = Math.max(0, Math.min(progress, 100));
		this.currentStep = currentStep;
		this.error = null;
	}

	public void complete() {
		this.status = AnalysisJobStatus.COMPLETED;
		this.progress = 100;
		this.currentStep = "초안 생성 완료";
		this.error = null;
	}

	public void fail(String error) {
		this.status = AnalysisJobStatus.FAILED;
		this.currentStep = "분석 실패";
		this.error = error;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
