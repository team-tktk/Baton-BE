package com.baton.ai;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {

	Optional<AnalysisJob> findFirstByHandoverIdOrderByCreatedAtDesc(UUID handoverId);

	boolean existsByHandoverIdAndStatusIn(UUID handoverId, Collection<AnalysisJobStatus> statuses);
}
