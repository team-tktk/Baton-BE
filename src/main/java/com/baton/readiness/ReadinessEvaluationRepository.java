package com.baton.readiness;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadinessEvaluationRepository extends JpaRepository<ReadinessEvaluation, UUID> {

	Optional<ReadinessEvaluation> findFirstByHandoverIdOrderByCreatedAtDesc(UUID handoverId);

	Optional<ReadinessEvaluation> findFirstByHandoverIdAndContentHashOrderByCreatedAtDesc(
			UUID handoverId, String contentHash);
}
