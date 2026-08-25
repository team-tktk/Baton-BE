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
	 * 이름/팀 부분검색(대소문자 무시). id 키셋 커서 페이지네이션.
	 * q가 null이면 전체(검색어 없이 드롭다운에 전체 구성원 노출), cursor가 null이면 처음부터.
	 * excludeId가 있으면 그 사용자(보통 본인)를 결과에서 제외한다. 정렬은 id 오름차순 고정으로 커서 일관성 보장.
	 */
	@Query("""
			SELECT u FROM User u
			WHERE (:cursor IS NULL OR u.id > :cursor)
			  AND (:excludeId IS NULL OR u.id <> :excludeId)
			  AND (:q IS NULL
			       OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
			       OR LOWER(u.team) LIKE LOWER(CONCAT('%', :q, '%')))
			ORDER BY u.id ASC
			""")
	List<User> searchByKeyword(@Param("q") String q, @Param("excludeId") UUID excludeId,
			@Param("cursor") UUID cursor, Pageable pageable);
}
