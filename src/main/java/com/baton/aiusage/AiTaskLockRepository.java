package com.baton.aiusage;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTaskLockRepository extends JpaRepository<AiTaskLock, String> {

	/** 비어 있거나 만료된 잠금만 가져간다. 가져갔으면 1, 다른 요청이 실행 중이면 0. */
	@Modifying
	@Query(value = """
			INSERT INTO ai_task_locks (lock_key, owner_token, acquired_at, expires_at)
			VALUES (:lockKey, :ownerToken, :now, :expiresAt)
			ON CONFLICT (lock_key) DO UPDATE
			   SET owner_token = EXCLUDED.owner_token,
			       acquired_at = EXCLUDED.acquired_at,
			       expires_at  = EXCLUDED.expires_at
			 WHERE ai_task_locks.expires_at <= :now
			""", nativeQuery = true)
	int tryAcquire(@Param("lockKey") String lockKey, @Param("ownerToken") String ownerToken,
			@Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

	/** 내가 잡은 잠금만 푼다(만료 후 다른 요청이 가져간 잠금은 건드리지 않는다). */
	@Modifying
	@Query("delete from AiTaskLock l where l.lockKey = :lockKey and l.ownerToken = :ownerToken")
	int release(@Param("lockKey") String lockKey, @Param("ownerToken") String ownerToken);

	@Query("select l.lockKey from AiTaskLock l where l.lockKey in :lockKeys and l.expiresAt > :now")
	List<String> findActiveKeys(@Param("lockKeys") Collection<String> lockKeys, @Param("now") Instant now);

	@Modifying
	@Query("delete from AiTaskLock l where l.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);
}
