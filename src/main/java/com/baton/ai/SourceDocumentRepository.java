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

	/** 업로드 용량 상한 체크용 — 해당 인수인계에 이미 쌓인 파일들의 총 용량(바이트). */
	@Query("SELECT COALESCE(SUM(s.fileSize), 0) FROM SourceDocument s WHERE s.handoverId = :handoverId")
	long sumFileSizeByHandoverId(@Param("handoverId") UUID handoverId);

	/** 계정 전체 업로드 용량 상한 체크용 — 이 사람이 인계자인 모든 인수인계에 쌓인 파일 총 용량(바이트). */
	@Query("""
			SELECT COALESCE(SUM(s.fileSize), 0) FROM SourceDocument s
			WHERE s.handoverId IN (SELECT h.id FROM Handover h WHERE h.ownerId = :ownerId)
			""")
	long sumFileSizeByOwnerId(@Param("ownerId") UUID ownerId);

	/**
	 * 목록 화면용 — 여러 인수인계의 첨부 파일 개수를 한 번에 집계한다(N+1 방지).
	 * 결과는 Object[]{handoverId(UUID), count(Long)} 행들. 파일이 0개인 인수인계는 결과에 없다.
	 */
	@Query("SELECT s.handoverId, COUNT(s) FROM SourceDocument s WHERE s.handoverId IN :handoverIds GROUP BY s.handoverId")
	List<Object[]> countGroupedByHandoverIds(@Param("handoverIds") Collection<UUID> handoverIds);
}
