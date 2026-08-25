package com.baton.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClarificationQuestionRepository extends JpaRepository<ClarificationQuestion, UUID> {

	List<ClarificationQuestion> findAllByHandoverId(UUID handoverId);

	void deleteAllByHandoverId(UUID handoverId);
}
