package com.baton.masking;

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
 * 마스킹 검수 API 권한 검사. 검수 화면에는 가리기 전 원문이 나오므로 인계자만 허용한다.
 * participants(LAZY)를 읽는 권한 검사만 짧은 트랜잭션에서 끝낸다(ReadinessAccess와 같은 방식).
 */
@Component
@RequiredArgsConstructor
public class MaskingAccess {

	private final HandoverRepository handoverRepository;
	private final HandoverPermission handoverPermission;
	private final AuthService authService;

	@Transactional(readOnly = true)
	public void requireOwner(UUID handoverId, Authentication authentication) {
		Handover handover = handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
		handoverPermission.requireOwner(handover, authService.getByEmail(authentication.getName()).getId());
	}
}
