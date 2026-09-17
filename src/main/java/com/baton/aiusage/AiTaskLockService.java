package com.baton.aiusage;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 인수인계 단위 AI 작업 중복 실행 방지. 버튼을 여러 번 누르거나 다른 서버로 요청이 나뉘어도
 * 같은 인수인계의 같은 기능은 한 번에 하나만 돈다. 이미 실행 중이면 409(AI_TASK_ALREADY_RUNNING).
 *
 * 잠금 획득·해제는 각각 별도 트랜잭션으로 즉시 커밋한다 — 호출하는 쪽 트랜잭션 안에 묶이면
 * 커밋 전까지 다른 서버에서 잠금이 보이지 않아 중복 실행을 못 막는다.
 */
@Service
@Slf4j
public class AiTaskLockService {

	private final AiTaskLockRepository lockRepository;
	private final AiUsageProperties properties;
	private final Clock clock;
	private final TransactionTemplate newTransaction;

	public AiTaskLockService(AiTaskLockRepository lockRepository, AiUsageProperties properties, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.lockRepository = lockRepository;
		this.properties = properties;
		this.clock = clock;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public <T> T runExclusive(AiTask task, UUID handoverId, Supplier<T> work) {
		String key = key(task, handoverId);
		String token = UUID.randomUUID().toString();
		Instant now = clock.instant();
		Integer acquired = newTransaction.execute(
				status -> lockRepository.tryAcquire(key, token, now, now.plus(properties.taskLockTtl())));
		if (acquired == null || acquired == 0) {
			throw new BusinessException(ErrorCode.AI_TASK_ALREADY_RUNNING,
					"이미 %s이(가) 진행 중이에요. 끝난 뒤 다시 시도해주세요.".formatted(task.getLabel()));
		}
		try {
			return work.get();
		} finally {
			try {
				newTransaction.executeWithoutResult(status -> lockRepository.release(key, token));
			} catch (RuntimeException e) {
				// 못 풀어도 taskLockTtl 뒤 자동으로 풀린다. 원래 결과/예외를 덮지 않는다.
				log.warn("[*] AI task lock release failed: key={}", key, e);
			}
		}
	}

	/** 인수인계에서 지금 실행 중인 작업 목록(버튼 "처리 중" 표시용). */
	public List<AiTask> runningTasks(UUID handoverId) {
		List<String> keys = Arrays.stream(AiTask.values()).map(task -> key(task, handoverId)).toList();
		List<String> active = lockRepository.findActiveKeys(keys, clock.instant());
		return Arrays.stream(AiTask.values())
				.filter(task -> active.contains(key(task, handoverId)))
				.toList();
	}

	static String key(AiTask task, UUID handoverId) {
		return task.name() + ":" + handoverId;
	}
}
