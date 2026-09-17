package com.baton.handover;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.AuthService;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * AI를 호출하는 API의 권한 검사. AI 호출은 오래 걸리므로 컨트롤러 전체를 트랜잭션으로 묶지 않고,
 * participants(LAZY)를 읽는 권한 검사만 짧은 트랜잭션에서 끝낸다. 둘 다 현재 사용자 id를 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class HandoverAccess {

	private final HandoverRepository handoverRepository;
	private final HandoverPermission handoverPermission;
	private final AuthService authService;

	@Transactional(readOnly = true)
	public UUID requireViewer(UUID handoverId, Authentication authentication) {
		UUID userId = currentUserId(authentication);
		handoverPermission.requireViewer(loadHandover(handoverId), userId);
		return userId;
	}

	@Transactional(readOnly = true)
	public UUID requireOwner(UUID handoverId, Authentication authentication) {
		UUID userId = currentUserId(authentication);
		handoverPermission.requireOwner(loadHandover(handoverId), userId);
		return userId;
	}

	private Handover loadHandover(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	private UUID currentUserId(Authentication authentication) {
		return authService.getByEmail(authentication.getName()).getId();
	}
}
