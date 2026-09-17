package com.baton.aiusage;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 같은 인수인계의 같은 AI 작업이 동시에 두 번 돌지 않게 하는 잠금(행 하나 = 실행 중 작업 하나).
 * 서버가 죽어 풀지 못한 잠금은 expires_at이 지나면 다음 요청이 가져간다.
 * 쓰기는 AiTaskLockService의 네이티브 쿼리(INSERT ... ON CONFLICT)로만 한다.
 */
@Entity
@Table(name = "ai_task_locks", indexes = {
		@Index(name = "idx_ai_task_locks_expires", columnList = "expires_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiTaskLock {

	@Id
	@Column(name = "lock_key", length = 100)
	private String lockKey;

	@Column(name = "owner_token", nullable = false, length = 36)
	private String ownerToken;

	@Column(name = "acquired_at", nullable = false)
	private Instant acquiredAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
}
