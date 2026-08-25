package com.baton.handover;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

/**
 * 인수인계 접근 권한 검사기. URL의 역할 값이 아니라 로그인 사용자와 Handover의 실제 관계로 판정한다.
 * files/review/AI 등 다른 도메인 컨트롤러도 이 컴포넌트를 재사용해 진입부에서 권한을 확인한다.
 */
@Component
public class HandoverPermission {

	/** 열람 권한(인계자·인수자·관리자). 없으면 403. */
	public void requireViewer(Handover handover, UUID userId) {
		if (!handover.isViewableBy(userId)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN);
		}
	}

	/** 인계자 본인만 허용. 없으면 403. */
	public void requireOwner(Handover handover, UUID userId) {
		if (!handover.isOwner(userId)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN);
		}
	}

	/** 관리자(REVIEWER)만 허용. */
	public void requireReviewer(Handover handover, UUID userId) {
		if (!handover.hasParticipant(userId, ParticipantRole.REVIEWER)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN);
		}
	}

	/** 인수자(RECIPIENT)만 허용. */
	public void requireRecipient(Handover handover, UUID userId) {
		if (!handover.hasParticipant(userId, ParticipantRole.RECIPIENT)) {
			throw new BusinessException(ErrorCode.HANDOVER_FORBIDDEN);
		}
	}

	/** 인계자 본인 + DRAFT 단계에서만 허용(기본정보 수정/삭제). */
	public void requireOwnerCanEditDraft(Handover handover, UUID userId) {
		requireOwner(handover, userId);
		if (!handover.getStatus().isEditableDraft()) {
			throw new BusinessException(ErrorCode.HANDOVER_NOT_EDITABLE);
		}
	}
}
