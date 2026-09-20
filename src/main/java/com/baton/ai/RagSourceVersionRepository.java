package com.baton.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagSourceVersionRepository extends JpaRepository<RagSourceVersion, UUID> {
	boolean existsBySourceIdAndContentHash(UUID sourceId, String contentHash);
	List<RagSourceVersion> findAllByHandoverIdOrderByArchivedAtDesc(UUID handoverId, Pageable pageable);
	List<RagSourceVersion> findAllByHandoverIdAndSourceIdOrderByArchivedAtDesc(
			UUID handoverId, UUID sourceId, Pageable pageable);
}
