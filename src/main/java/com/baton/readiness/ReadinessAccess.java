package com.baton.readiness;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverPermission;
import com.baton.handover.HandoverRepository;

import lombok.RequiredArgsConstructor;

/**
 * 준비도 API 권한 검사. 평가·보완안 생성은 AI를 호출해 오래 걸리므로 컨트롤러 전체를 트랜잭션으로 묶지 않고,
 * participants(LAZY)를 읽는 권한 검사만 짧은 트랜잭션에서 끝낸다.
 */
@Component
@RequiredArgsConstructor
public class ReadinessAccess {

	private final HandoverRepository handoverRepository;
	private final HandoverPermission handoverPermission;
	private final AuthService authService;

	@Transactional(readOnly = true)
	public void requireViewer(UUID handoverId, Authentication authentication) {
		handoverPermission.requireViewer(loadHandover(handoverId), currentUserId(authentication));
	}

	@Transactional(readOnly = true)
	public void requireOwner(UUID handoverId, Authentication authentication) {
		handoverPermission.requireOwner(loadHandover(handoverId), currentUserId(authentication));
	}

	private Handover loadHandover(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
