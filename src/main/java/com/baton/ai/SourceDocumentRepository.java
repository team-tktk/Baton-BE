package com.baton.ai;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {

	List<SourceDocument> findAllByHandoverId(UUID handoverId);

	/** 상세 화면 등 단건 인수인계의 첨부 파일 개수. */
	long countByHandoverId(UUID handoverId);

	/**
	 * 목록 화면용 — 여러 인수인계의 첨부 파일 개수를 한 번에 집계한다(N+1 방지).
	 * 결과는 Object[]{handoverId(UUID), count(Long)} 행들. 파일이 0개인 인수인계는 결과에 없다.
	 */
	@Query("SELECT s.handoverId, COUNT(s) FROM SourceDocument s WHERE s.handoverId IN :handoverIds GROUP BY s.handoverId")
	List<Object[]> countGroupedByHandoverIds(@Param("handoverIds") Collection<UUID> handoverIds);
}
