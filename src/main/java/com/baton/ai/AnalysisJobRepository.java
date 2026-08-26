package com.baton.ai;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

	Optional<AnalysisJob> findFirstByHandoverIdOrderByCreatedAtDesc(UUID handoverId);

	boolean existsByHandoverIdAndStatusIn(UUID handoverId, Collection<AnalysisJobStatus> statuses);

	/** 진행 상태(active)인데 마지막 갱신이 threshold보다 오래된 작업 — 사실상 고착된 작업. */
	List<AnalysisJob> findByStatusInAndUpdatedAtBefore(Collection<AnalysisJobStatus> statuses, Instant threshold);
}
