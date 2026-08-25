package com.baton.ai;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HandoverDraftRepository extends JpaRepository<HandoverDraft, UUID> {

	Optional<HandoverDraft> findByHandoverId(UUID handoverId);
}
