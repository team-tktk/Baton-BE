package com.baton.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

	List<SourceDocument> findAllByHandoverId(UUID handoverId);
}
