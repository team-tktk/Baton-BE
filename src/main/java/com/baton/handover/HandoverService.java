package com.baton.handover;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.auth.UserRepository;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.dto.CreateHandoverRequest;
import com.baton.handover.dto.HandoverListResponse;
import com.baton.handover.dto.HandoverResponse;
import com.baton.handover.dto.HandoverSummaryResponse;
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
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	/** 인계자가 새 초안을 만든다. 참여자/업무범위가 함께 오면 초기값으로 채운다. */
	@Transactional
	public HandoverResponse create(UUID ownerId, CreateHandoverRequest req) {
		String title = (req.title() == null || req.title().isBlank()) ? DEFAULT_TITLE : req.title();
		Handover handover = Handover.create(ownerId, title);

		applyRecipients(handover, req.recipientIds());
		applyReviewers(handover, req.reviewerIds());
		applyWorkScopes(handover, req.workScopes());

		handoverRepository.save(handover);
		return HandoverResponse.of(handover, ownerId);
	}

	/** 인계자가 보낸 인수인계 목록(상태 필터 + 커서 페이지네이션 + 상태별 개수). */
	@Transactional(readOnly = true)
	public HandoverListResponse listSent(UUID userId, HandoverStatus status, UUID cursor, int size) {
		int pageSize = clampSize(size);
		List<Handover> rows = handoverRepository.findSent(userId, status, cursor, PageRequest.of(0, pageSize + 1));
		Map<HandoverStatus, Long> counts = toStatusCounts(handoverRepository.countSentByStatus(userId));
		return buildList(rows, pageSize, counts, (h, v) -> HandoverSummaryResponse.ofSent(h), userId);
	}

	/** 인수자가 받은 인수인계 목록. */
	@Transactional(readOnly = true)
	public HandoverListResponse listReceived(UUID userId, HandoverStatus status, UUID cursor, int size) {
		int pageSize = clampSize(size);
		List<Handover> rows = handoverRepository.findReceived(
				userId, ParticipantRole.RECIPIENT, status, cursor, PageRequest.of(0, pageSize + 1));
		Map<HandoverStatus, Long> counts = toStatusCounts(
				handoverRepository.countReceivedByStatus(userId, ParticipantRole.RECIPIENT));
		return buildList(rows, pageSize, counts, HandoverSummaryResponse::ofReceived, userId);
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
		if (req.reviewerIds() != null) {
			validateUsersExist(req.reviewerIds());
			handover.replaceReviewers(req.reviewerIds());
		}
		if (req.workScopes() != null) {
			handover.replaceWorkScopes(req.workScopes().stream()
					.map(w -> handover.newWorkScope(w.title(), w.description()))
					.toList());
		}
		return HandoverResponse.of(handover, ownerId);
	}

	/**
	 * 인계자가 인수인계를 제출한다(→ PENDING_REVIEW). 멱등: 이미 제출된 건이면 상태를 바꾸지 않는다.
	 * 승인/완료 등 재제출 불가 단계면 409.
	 */
	@Transactional
	public HandoverResponse submit(UUID handoverId, UUID ownerId) {
		Handover handover = load(handoverId);
		permission.requireOwner(handover, ownerId);

		if (!handover.isSubmitted()) {
			if (!handover.isSubmittable()) {
				throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE, "제출할 수 없는 상태입니다: " + handover.getStatus());
			}
			handover.markSubmitted();
		}
		return HandoverResponse.of(handover, ownerId);
	}

	/** 인수자가 문서를 처음 열어 수신 확인 처리(receiptStatus → READ). 멱등. */
	@Transactional
	public HandoverResponse acknowledge(UUID handoverId, UUID userId) {
		Handover handover = load(handoverId);
		permission.requireRecipient(handover, userId);
		handover.acknowledgeBy(userId);
		return HandoverResponse.of(handover, userId);
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

	/** size+1건에서 다음 페이지 유무를 판별하고 요약 DTO로 매핑한다. */
	private HandoverListResponse buildList(List<Handover> rows, int pageSize, Map<HandoverStatus, Long> counts,
			BiFunction<Handover, UUID, HandoverSummaryResponse> mapper, UUID viewerId) {
		boolean hasNext = rows.size() > pageSize;
		List<Handover> page = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = hasNext ? page.get(page.size() - 1).getId().toString() : null;
		List<HandoverSummaryResponse> items = page.stream().map(h -> mapper.apply(h, viewerId)).toList();
		return new HandoverListResponse(items, nextCursor, hasNext, counts);
	}

	/** GROUP BY 결과(Object[]{status, count})를 모든 상태 0으로 초기화한 맵에 덮어쓴다. */
	private Map<HandoverStatus, Long> toStatusCounts(List<Object[]> grouped) {
		Map<HandoverStatus, Long> counts = new EnumMap<>(HandoverStatus.class);
		for (HandoverStatus s : HandoverStatus.values()) {
			counts.put(s, 0L);
		}
		for (Object[] row : grouped) {
			counts.put((HandoverStatus) row[0], (Long) row[1]);
		}
		return counts;
	}

	private int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}

	private void applyRecipients(Handover handover, List<UUID> recipientIds) {
		if (recipientIds == null) {
			return;
		}
		validateUsersExist(recipientIds);
		recipientIds.forEach(handover::addRecipient);
	}

	private void applyReviewers(Handover handover, List<UUID> reviewerIds) {
		if (reviewerIds == null) {
			return;
		}
		validateUsersExist(reviewerIds);
		reviewerIds.forEach(handover::addReviewer);
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
