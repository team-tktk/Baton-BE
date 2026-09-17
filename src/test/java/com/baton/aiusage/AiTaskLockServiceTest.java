package com.baton.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

@ExtendWith(MockitoExtension.class)
class AiTaskLockServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-17T05:30:00Z");

	@Mock
	private AiTaskLockRepository lockRepository;
	@Mock
	private PlatformTransactionManager transactionManager;

	private AiTaskLockService service;
	private final UUID handoverId = UUID.randomUUID();
	private final String key = "READINESS_EVALUATION:" + handoverId;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		AiUsageProperties properties = new AiUsageProperties(true, null, Duration.ofMinutes(5), null, null);
		service = new AiTaskLockService(lockRepository, properties, Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
	}

	@Test
	void runsTaskAndReleasesOwnLock() {
		when(lockRepository.tryAcquire(eq(key), anyString(), eq(NOW), eq(NOW.plus(Duration.ofMinutes(5))))).thenReturn(1);

		String result = service.runExclusive(AiTask.READINESS_EVALUATION, handoverId, () -> "done");

		assertThat(result).isEqualTo("done");
		verify(lockRepository).release(eq(key), anyString());
	}

	@Test
	void rejectsWhenSameTaskIsRunning() {
		when(lockRepository.tryAcquire(eq(key), anyString(), any(), any())).thenReturn(0);
		AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> service.runExclusive(AiTask.READINESS_EVALUATION, handoverId, () -> {
			ran.set(true);
			return null;
		})).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_TASK_ALREADY_RUNNING));

		assertThat(ran).isFalse();
		verify(lockRepository, org.mockito.Mockito.never()).release(anyString(), anyString());
	}

	@Test
	void releasesLockWhenTaskFails() {
		when(lockRepository.tryAcquire(eq(key), anyString(), any(), any())).thenReturn(1);

		assertThatThrownBy(() -> service.runExclusive(AiTask.READINESS_EVALUATION, handoverId, () -> {
			throw new BusinessException(ErrorCode.AI_USAGE_LIMIT_EXCEEDED);
		})).isInstanceOf(BusinessException.class);

		verify(lockRepository).release(eq(key), anyString());
	}

	@Test
	void listsRunningTasks() {
		when(lockRepository.findActiveKeys(any(), eq(NOW))).thenReturn(List.of("READINESS_FIX:" + handoverId));

		assertThat(service.runningTasks(handoverId)).containsExactly(AiTask.READINESS_FIX);
	}
}
