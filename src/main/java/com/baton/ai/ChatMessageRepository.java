package com.baton.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

	List<ChatMessage> findAllByHandoverIdOrderByCreatedAtAsc(UUID handoverId);

	/** 처음 페이지. Postgres가 "IS NULL"로만 쓰이는 파라미터의 타입을 추론하지 못해 cursor 유무로 쿼리를 분리한다. */
	List<ChatMessage> findByHandoverIdOrderByCreatedAtAsc(UUID handoverId, Pageable pageable);

	/** 커서 이후 페이지(오래된 순). */
	List<ChatMessage> findByHandoverIdAndCreatedAtAfterOrderByCreatedAtAsc(UUID handoverId, Instant cursor, Pageable pageable);
}
