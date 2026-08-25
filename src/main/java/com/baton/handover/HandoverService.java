package com.baton.handover;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.UserRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.dto.CreateHandoverRequest;
import com.baton.handover.dto.HandoverResponse;
import com.baton.handover.dto.UpdateHandoverRequest;
import com.baton.handover.dto.WorkScopeInput;

import lombok.RequiredArgsConstructor;

/**
 * 인수인계 기본 정보(제목/참여자/업무범위) 생성·조회·수정·삭제.
 * 상태 전이(제출/승인 등)와 문서 본문은 이후 슬라이스에서 별도 서비스가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class HandoverService {

	private final HandoverRepository handoverRepository;
	private final UserRepository userRepository;
	private final HandoverPermission permission;

	private static final String DEFAULT_TITLE = "제목 없는 인수인계";

	/** 인계자가 새 초안을 만든다. 참여자/업무범위가 함께 오면 초기값으로 채운다. */
	@Transactional
	public HandoverResponse create(UUID ownerId, CreateHandoverRequest req) {
		String title = (req.title() == null || req.title().isBlank()) ? DEFAULT_TITLE : req.title();
		Handover handover = Handover.create(ownerId, title);

		applyRecipients(handover, req.recipientIds());
		applyWorkScopes(handover, req.workScopes());

		handoverRepository.save(handover);
		return HandoverResponse.of(handover, ownerId);
	}

	@Transactional(readOnly = true)
	public HandoverResponse getForViewer(UUID handoverId, UUID viewerId) {
		Handover handover = load(handoverId);
		permission.requireViewer(handover, viewerId);
		return HandoverResponse.of(handover, viewerId);
	}

	/** 인계자가 DRAFT 단계에서 기본 정보를 수정한다. null 필드는 변경하지 않는다. */
	@Transactional
	public HandoverResponse update(UUID handoverId, UUID ownerId, UpdateHandoverRequest req) {
		Handover handover = load(handoverId);
		permission.requireOwnerCanEditDraft(handover, ownerId);

		if (req.title() != null && !req.title().isBlank()) {
			handover.rename(req.title());
		}
		if (req.recipientIds() != null) {
			validateUsersExist(req.recipientIds());
			handover.replaceRecipients(req.recipientIds());
		}
		if (req.workScopes() != null) {
			handover.replaceWorkScopes(req.workScopes().stream()
					.map(w -> handover.newWorkScope(w.title(), w.description()))
					.toList());
		}
		return HandoverResponse.of(handover, ownerId);
	}

	/** 인계자가 제출 전 초안을 삭제한다. */
	@Transactional
	public void delete(UUID handoverId, UUID ownerId) {
		Handover handover = load(handoverId);
		permission.requireOwnerCanEditDraft(handover, ownerId);
		handoverRepository.delete(handover);
	}

	// ── 내부 헬퍼 ────────────────────────────────────────────

	private Handover load(UUID handoverId) {
		return handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
	}

	private void applyRecipients(Handover handover, List<UUID> recipientIds) {
		if (recipientIds == null) {
			return;
		}
		validateUsersExist(recipientIds);
		recipientIds.forEach(handover::addRecipient);
	}

	private void applyWorkScopes(Handover handover, List<WorkScopeInput> scopes) {
		if (scopes == null) {
			return;
		}
		scopes.forEach(w -> handover.addWorkScope(w.title(), w.description()));
	}

	/** 참여자로 지정한 사용자가 모두 실재하는지 확인. 하나라도 없으면 400. */
	private void validateUsersExist(List<UUID> userIds) {
		for (UUID userId : userIds) {
			if (!userRepository.existsById(userId)) {
				throw new BusinessException(ErrorCode.HANDOVER_INVALID_PARTICIPANT,
						"존재하지 않는 사용자입니다: " + userId);
			}
		}
	}
}
