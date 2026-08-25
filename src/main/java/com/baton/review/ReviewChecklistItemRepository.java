package com.baton.review;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewChecklistItemRepository extends JpaRepository<ReviewChecklistItem, UUID> {

	List<ReviewChecklistItem> findAllByHandoverId(UUID handoverId);

	void deleteAllByHandoverId(UUID handoverId);
}
