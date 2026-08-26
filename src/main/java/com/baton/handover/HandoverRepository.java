package com.baton.handover;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 인수인계 저장소.
 *
 * 참여자/업무범위는 서비스의 트랜잭션 안에서 lazy 로딩해 DTO로 매핑한다.
 * (두 List 컬렉션을 한 쿼리로 동시 fetch하면 Hibernate MultipleBagFetchException이 나므로
 *  @EntityGraph로 함께 끌어오지 않는다.)
 *
 * 목록은 id 키셋 커서 페이지네이션(정렬 id ASC 고정)으로 일관성을 보장한다.
 */
public interface HandoverRepository extends JpaRepository<Handover, UUID> {

	/** 분석 작업 중복 생성을 막기 위해 해당 인수인계 행을 쓰기 잠금으로 읽는다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT h FROM Handover h WHERE h.id = :handoverId")
	Optional<Handover> findByIdForUpdate(@Param("handoverId") UUID handoverId);

	/** 인계자(owner)가 보낸 목록. status가 null이면 전체, cursor가 null이면 처음부터. */
	@Query("""
			SELECT h FROM Handover h
			WHERE h.ownerId = :userId
			  AND (:status IS NULL OR h.status = :status)
			  AND (:cursor IS NULL OR h.id > :cursor)
			ORDER BY h.id ASC
			""")
	List<Handover> findSent(@Param("userId") UUID userId,
			@Param("status") HandoverStatus status,
			@Param("cursor") UUID cursor,
			Pageable pageable);

	/** 인수자(participant, RECIPIENT)가 받은 목록. */
	@Query("""
			SELECT h FROM Handover h JOIN h.participants p
			WHERE p.userId = :userId AND p.role = :role
			  AND (:status IS NULL OR h.status = :status)
			  AND (:cursor IS NULL OR h.id > :cursor)
			ORDER BY h.id ASC
			""")
	List<Handover> findReceived(@Param("userId") UUID userId,
			@Param("role") ParticipantRole role,
			@Param("status") HandoverStatus status,
			@Param("cursor") UUID cursor,
			Pageable pageable);

	/**
	 * 인수자 관점 필터(UNREAD/IN_PROGRESS/COMPLETED)로 받은 목록을 조회한다.
	 * 필터는 (receiptStatus, completed) 두 조건으로 분해해 넘긴다:
	 *  - receiptStatus IS NULL이면 열람 상태 무관, 아니면 해당 열람 상태만
	 *  - completed IS NULL이면 완료 여부 무관, TRUE면 완료건만, FALSE면 미완료건만
	 */
	@Query("""
			SELECT h FROM Handover h JOIN h.participants p
			WHERE p.userId = :userId AND p.role = :role
			  AND (:receiptStatus IS NULL OR p.receiptStatus = :receiptStatus)
			  AND (:completed IS NULL
			       OR (:completed = TRUE AND h.status = :completedStatus)
			       OR (:completed = FALSE AND h.status <> :completedStatus))
			  AND (:cursor IS NULL OR h.id > :cursor)
			ORDER BY h.id ASC
			""")
	List<Handover> findReceivedFiltered(@Param("userId") UUID userId,
			@Param("role") ParticipantRole role,
			@Param("receiptStatus") ReceiptStatus receiptStatus,
			@Param("completed") Boolean completed,
			@Param("completedStatus") HandoverStatus completedStatus,
			@Param("cursor") UUID cursor,
			Pageable pageable);

	/** 받은 목록을 (본상태, 수신상태)별로 집계한다. 필터 탭 뱃지 개수를 서비스에서 버킷팅하는 데 쓴다. */
	@Query("""
			SELECT h.status, p.receiptStatus, COUNT(h) FROM Handover h JOIN h.participants p
			WHERE p.userId = :userId AND p.role = :role
			GROUP BY h.status, p.receiptStatus
			""")
	List<Object[]> countReceivedGrouped(@Param("userId") UUID userId, @Param("role") ParticipantRole role);

	/** 보낸 목록의 상태별 개수. */
	@Query("SELECT h.status, COUNT(h) FROM Handover h WHERE h.ownerId = :userId GROUP BY h.status")
	List<Object[]> countSentByStatus(@Param("userId") UUID userId);

	/** 받은 목록의 상태별 개수. */
	@Query("""
			SELECT h.status, COUNT(h) FROM Handover h JOIN h.participants p
			WHERE p.userId = :userId AND p.role = :role
			GROUP BY h.status
			""")
	List<Object[]> countReceivedByStatus(@Param("userId") UUID userId, @Param("role") ParticipantRole role);
}
