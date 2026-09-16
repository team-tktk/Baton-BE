package com.baton.ai;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface HandoverDraftRepository extends JpaRepository<HandoverDraft, UUID> {

	Optional<HandoverDraft> findByHandoverId(UUID handoverId);

	/** 버전 확인 후 부분 수정할 때 쓴다. 확인과 저장 사이에 다른 저장이 끼어들지 못하게 행을 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT d FROM HandoverDraft d WHERE d.handoverId = :handoverId")
	Optional<HandoverDraft> findByHandoverIdForUpdate(@Param("handoverId") UUID handoverId);
}
