package com.baton.readiness;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadinessFixRepository extends JpaRepository<ReadinessFix, UUID> {

	Optional<ReadinessFix> findByIdAndHandoverId(UUID id, UUID handoverId);
}
