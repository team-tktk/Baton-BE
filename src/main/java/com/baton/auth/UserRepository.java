package com.baton.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/**
	 * 검색어 없이 전체 구성원을 커서 순으로(드롭다운용). excludeId(보통 본인)는 제외, cursor null이면 처음부터.
	 *
	 * 검색 쿼리와 분리한 이유: LIKE에 null을 넘기면 PostgreSQL이 그 파라미터를 bytea로 추론해
	 * `lower(bytea) does not exist`로 터진다(`:q IS NULL OR ...`로 감싸도 플랜 단계에서 실패).
	 * 그래서 검색어 없는 경로는 LOWER/LIKE 자체를 태우지 않는다.
	 */
	@Query("""
			SELECT u FROM User u
			WHERE (:cursor IS NULL OR u.id > :cursor)
			  AND (:excludeId IS NULL OR u.id <> :excludeId)
			ORDER BY u.id ASC
			""")
	List<User> findPage(@Param("excludeId") UUID excludeId, @Param("cursor") UUID cursor, Pageable pageable);

	/**
	 * 이름/팀 부분검색(대소문자 무시). id 키셋 커서 페이지네이션.
	 * q는 non-null(호출부에서 보장), cursor null이면 처음부터. excludeId(보통 본인)는 제외.
	 */
	@Query("""
			SELECT u FROM User u
			WHERE (:cursor IS NULL OR u.id > :cursor)
			  AND (:excludeId IS NULL OR u.id <> :excludeId)
			  AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
			       OR LOWER(u.team) LIKE LOWER(CONCAT('%', :q, '%')))
			ORDER BY u.id ASC
			""")
	List<User> searchByKeyword(@Param("q") String q, @Param("excludeId") UUID excludeId,
			@Param("cursor") UUID cursor, Pageable pageable);
}
