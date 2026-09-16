package com.baton.masking;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaskingCandidateRepository extends JpaRepository<MaskingCandidate, UUID> {

	/** 검수 화면용 — 원문 위치 순서대로. */
	List<MaskingCandidate> findAllBySourceDocumentIdOrderByStartOffset(UUID sourceDocumentId);

	/** "남은 확인 개수". 0이어야 검수를 확정할 수 있다. */
	long countBySourceDocumentIdAndNeedsReviewTrueAndReviewedFalse(UUID sourceDocumentId);

	/**
	 * 파일 목록용 — 인수인계의 파일별 "남은 확인 개수"를 한 번에 집계한다(N+1 방지).
	 * 결과는 Object[]{sourceDocumentId(UUID), count(Long)} 행들. 남은 게 없는 파일은 결과에 없다.
	 */
	@Query("""
			SELECT c.sourceDocumentId, COUNT(c) FROM MaskingCandidate c
			WHERE c.handoverId = :handoverId AND c.needsReview = true AND c.reviewed = false
			GROUP BY c.sourceDocumentId
			""")
	List<Object[]> countPendingReviewGroupedBySourceDocument(@Param("handoverId") UUID handoverId);

	/** 파일 삭제·재추출 시 해당 파일의 후보를 한 번에 지운다. */
	@Modifying
	@Query("DELETE FROM MaskingCandidate c WHERE c.sourceDocumentId = :sourceDocumentId")
	void deleteAllBySourceDocumentId(@Param("sourceDocumentId") UUID sourceDocumentId);
}
