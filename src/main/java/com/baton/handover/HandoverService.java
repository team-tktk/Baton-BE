package com.baton.handover;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.SourceDocumentRepository;
import com.baton.auth.UserDirectory;
import com.baton.auth.UserRepository;
import com.baton.auth.dto.UserSummaryResponse;
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
	private final UserDirectory userDirectory;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final HandoverPermission permission;

	private static final String DEFAULT_TITLE = "제목 없는 인수인계";
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 100;

	/**
	 * 인계자가 새 초안을 만든다. 참여자/업무범위가 함께 오면 초기값으로 채운다.
	 * 제목 정책: {@link #resolveTitle} — 제목 생략 시 첫 번째 업무범위 제목으로 자동 생성, 그것도 없으면 기본 제목.
	 */
	@Transactional
	public HandoverResponse create(UUID ownerId, CreateHandoverRequest req) {
		Handover handover = Handover.create(ownerId, resolveTitle(req.title(), req.workScopes()));

		validateParticipants(ownerId, req.recipientIds(), req.reviewerIds());
		applyRecipients(handover, req.recipientIds());
		applyReviewers(handover, req.reviewerIds());
		applyWorkScopes(handover, req.workScopes());

		handoverRepository.save(handover);
		return toResponse(handover, ownerId);
	}

	/**
	 * 생성 시 제목 결정 규칙(프론트 생성 화면에 제목 입력란이 없어 백엔드가 채운다):
	 * 1) 명시적으로 보낸 제목이 있으면 그대로,
	 * 2) 없으면 첫 번째 업무범위(workScopes[0].title)를 제목으로,
	 * 3) 그것도 없으면 기본 제목("제목 없는 인수인계").
	 */
	private String resolveTitle(String requestedTitle, List<WorkScopeInput> workScopes) {
		if (requestedTitle != null && !requestedTitle.isBlank()) {
			return requestedTitle;
		}
		if (workScopes != null && !workScopes.isEmpty()) {
			String first = workScopes.get(0).title();
			if (first != null && !first.isBlank()) {
				return first;
			}
		}
		return DEFAULT_TITLE;
	}

	/** 인계자가 보낸 인수인계 목록(상태 필터 + 커서 페이지네이션 + 상태별 개수). */
	@Transactional(readOnly = true)
	public HandoverListResponse listSent(UUID userId, HandoverStatus status, UUID cursor, int size) {
		int pageSize = clampSize(size);
		List<Handover> rows = handoverRepository.findSent(userId, status, cursor, PageRequest.of(0, pageSize + 1));
		Map<String, Long> counts = toStatusCounts(handoverRepository.countSentByStatus(userId));
		return buildList(rows, pageSize, counts, false, userId);
	}

	/**
	 * 인수자가 받은 인수인계 목록. 필터는 인수자 관점의 UNREAD/IN_PROGRESS/COMPLETED다(본 상태와 별개).
	 * 뱃지 개수도 같은 세 버킷 기준으로 내린다.
	 */
	@Transactional(readOnly = true)
	public HandoverListResponse listReceived(UUID userId, ReceivedFilter filter, UUID cursor, int size) {
		int pageSize = clampSize(size);
		List<Handover> rows = handoverRepository.findReceivedFiltered(
				userId, ParticipantRole.RECIPIENT,
				receiptStatusFor(filter), completedFor(filter), HandoverStatus.COMPLETED,
				cursor, PageRequest.of(0, pageSize + 1));
		Map<String, Long> counts = toReceivedCounts(
				handoverRepository.countReceivedGrouped(userId, ParticipantRole.RECIPIENT));
		return buildList(rows, pageSize, counts, true, userId);
	}

	/**
	 * 관리자(REVIEWER로 지정된 사람)의 검토 목록. 받은 목록과 동일한 쿼리를 role=REVIEWER로 재사용한다.
	 * 관리자는 수신 개념이 없어 receiptStatus는 담지 않는다(ofSent 매퍼).
	 */
	@Transactional(readOnly = true)
	public HandoverListResponse listReviews(UUID userId, HandoverStatus status, UUID cursor, int size) {
		int pageSize = clampSize(size);
		List<Handover> rows = handoverRepository.findReceived(
				userId, ParticipantRole.REVIEWER, status, cursor, PageRequest.of(0, pageSize + 1));
		Map<String, Long> counts = toStatusCounts(
				handoverRepository.countReceivedByStatus(userId, ParticipantRole.REVIEWER));
		return buildList(rows, pageSize, counts, false, userId);
	}

	@Transactional(readOnly = true)
	public HandoverResponse getForViewer(UUID handoverId, UUID viewerId) {
		Handover handover = load(handoverId);
		permission.requireViewer(handover, viewerId);
		return toResponse(handover, viewerId);
	}

	/** 인계자가 DRAFT 단계에서 기본 정보를 수정한다. null 필드는 변경하지 않는다. */
	@Transactional
	public HandoverResponse update(UUID handoverId, UUID ownerId, UpdateHandoverRequest req) {
		Handover handover = load(handoverId);
		permission.requireOwnerCanEditDraft(handover, ownerId);

		if (req.title() != null && !req.title().isBlank()) {
			handover.rename(req.title());
		}
		// 인수자/관리자 지정 규칙 검증 — 넘어오지 않은 쪽은 기존 참여자 기준으로 함께 검사한다.
		validateParticipants(handover.getOwnerId(),
				req.recipientIds() != null ? req.recipientIds() : currentParticipantIds(handover, ParticipantRole.RECIPIENT),
				req.reviewerIds() != null ? req.reviewerIds() : currentParticipantIds(handover, ParticipantRole.REVIEWER));
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
		return toResponse(handover, ownerId);
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
		return toResponse(handover, ownerId);
	}

	/** 인수자가 문서를 처음 열어 수신 확인 처리(receiptStatus → READ). 멱등. */
	@Transactional
	public HandoverResponse acknowledge(UUID handoverId, UUID userId) {
		Handover handover = load(handoverId);
		permission.requireRecipient(handover, userId);
		handover.acknowledgeBy(userId);
		return toResponse(handover, userId);
	}

	/** 인수자가 인수인계를 완료 처리(→ COMPLETED). 멱등. 제출된 적 없는 건은 409. */
	@Transactional
	public HandoverResponse complete(UUID handoverId, UUID userId) {
		Handover handover = load(handoverId);
		permission.requireRecipient(handover, userId);

		if (!handover.isCompleted()) {
			if (!handover.isCompletable()) {
				throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE, "아직 제출되지 않아 완료할 수 없습니다.");
			}
			handover.markCompleted();
		}
		return toResponse(handover, userId);
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

	/** 상세 응답 조립 — 이 인수인계에 등장하는 사용자(owner + 참여자)를 한 번에 요약으로 읽어 담는다. */
	private HandoverResponse toResponse(Handover handover, UUID viewerId) {
		Set<UUID> userIds = new HashSet<>();
		userIds.add(handover.getOwnerId());
		handover.getParticipants().forEach(p -> userIds.add(p.getUserId()));
		return HandoverResponse.of(handover, viewerId, userDirectory.summarize(userIds));
	}

	/**
	 * size+1건에서 다음 페이지 유무를 판별하고 요약 DTO로 매핑한다.
	 * 보낸 사람(owner) 요약과 첨부 파일 개수는 페이지 전체를 배치 조회해 N+1을 피한다.
	 * includeReceipt=true면(받은 목록) 현재 사용자의 수신 상태를 함께 담는다.
	 */
	private HandoverListResponse buildList(List<Handover> rows, int pageSize, Map<String, Long> counts,
			boolean includeReceipt, UUID viewerId) {
		boolean hasNext = rows.size() > pageSize;
		List<Handover> page = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = hasNext ? page.get(page.size() - 1).getId().toString() : null;

		Set<UUID> ownerIds = new HashSet<>();
		List<UUID> handoverIds = page.stream().map(Handover::getId).toList();
		page.forEach(h -> ownerIds.add(h.getOwnerId()));
		Map<UUID, UserSummaryResponse> owners = userDirectory.summarize(ownerIds);
		Map<UUID, Integer> fileCounts = fileCountsOf(handoverIds);

		List<HandoverSummaryResponse> items = page.stream()
				.map(h -> {
					UserSummaryResponse owner = owners.getOrDefault(h.getOwnerId(), UserSummaryResponse.unknown(h.getOwnerId()));
					int fileCount = fileCounts.getOrDefault(h.getId(), 0);
					return includeReceipt
							? HandoverSummaryResponse.ofReceived(h, viewerId, owner, fileCount)
							: HandoverSummaryResponse.ofSent(h, owner, fileCount);
				})
				.toList();
		return new HandoverListResponse(items, nextCursor, hasNext, counts);
	}

	/** 여러 인수인계의 첨부 파일 개수를 한 번에 집계해 맵으로. 파일 0개면 맵에 없다(getOrDefault로 0 처리). */
	private Map<UUID, Integer> fileCountsOf(List<UUID> handoverIds) {
		Map<UUID, Integer> counts = new LinkedHashMap<>();
		if (handoverIds.isEmpty()) {
			return counts;
		}
		for (Object[] row : sourceDocumentRepository.countGroupedByHandoverIds(handoverIds)) {
			counts.put((UUID) row[0], ((Long) row[1]).intValue());
		}
		return counts;
	}

	/** GROUP BY 결과(Object[]{status, count})를 모든 상태 0으로 초기화한 맵에 status 이름으로 담는다. */
	private Map<String, Long> toStatusCounts(List<Object[]> grouped) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (HandoverStatus s : HandoverStatus.values()) {
			counts.put(s.name(), 0L);
		}
		for (Object[] row : grouped) {
			counts.put(((HandoverStatus) row[0]).name(), (Long) row[1]);
		}
		return counts;
	}

	/** (본상태, 수신상태)별 집계를 인수자 관점 세 버킷(UNREAD/IN_PROGRESS/COMPLETED)으로 합산한다. */
	private Map<String, Long> toReceivedCounts(List<Object[]> grouped) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (ReceivedFilter f : ReceivedFilter.values()) {
			counts.put(f.name(), 0L);
		}
		for (Object[] row : grouped) {
			HandoverStatus status = (HandoverStatus) row[0];
			ReceiptStatus receipt = (ReceiptStatus) row[1];
			long count = (Long) row[2];
			ReceivedFilter bucket;
			if (status == HandoverStatus.COMPLETED) {
				bucket = ReceivedFilter.COMPLETED;
			} else if (receipt == ReceiptStatus.UNREAD) {
				bucket = ReceivedFilter.UNREAD;
			} else {
				bucket = ReceivedFilter.IN_PROGRESS;
			}
			counts.merge(bucket.name(), count, Long::sum);
		}
		return counts;
	}

	/** 필터 → 조회할 수신 상태(COMPLETED 필터는 열람 여부 무관이라 null). */
	private ReceiptStatus receiptStatusFor(ReceivedFilter filter) {
		if (filter == null) {
			return null;
		}
		return switch (filter) {
			case UNREAD -> ReceiptStatus.UNREAD;
			case IN_PROGRESS -> ReceiptStatus.READ;
			case COMPLETED -> null;
		};
	}

	/** 필터 → 완료 여부 조건(null=무관, TRUE=완료건만, FALSE=미완료건만). */
	private Boolean completedFor(ReceivedFilter filter) {
		if (filter == null) {
			return null;
		}
		return switch (filter) {
			case UNREAD, IN_PROGRESS -> Boolean.FALSE;
			case COMPLETED -> Boolean.TRUE;
		};
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

	/**
	 * 참여자 지정 규칙 검증(모두 400, code=HANDOVER_INVALID_PARTICIPANT):
	 * 1) 인계자(owner) 본인은 인수자/관리자로 지정할 수 없다.
	 * 2) 같은 사람을 인수자이자 관리자로 동시에 지정할 수 없다(자기가 받은 걸 자기가 승인 방지).
	 */
	private void validateParticipants(UUID ownerId, List<UUID> recipientIds, List<UUID> reviewerIds) {
		if (recipientIds != null && recipientIds.contains(ownerId)) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_PARTICIPANT, "본인을 인수자로 지정할 수 없습니다.");
		}
		if (reviewerIds != null && reviewerIds.contains(ownerId)) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_PARTICIPANT, "본인을 관리자로 지정할 수 없습니다.");
		}
		if (recipientIds != null && reviewerIds != null
				&& recipientIds.stream().anyMatch(reviewerIds::contains)) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_PARTICIPANT,
					"같은 사람을 인수자와 관리자로 동시에 지정할 수 없습니다.");
		}
	}

	/** 현재 인수인계의 특정 역할 참여자 userId 목록(update에서 넘어오지 않은 쪽 검증용). */
	private List<UUID> currentParticipantIds(Handover handover, ParticipantRole role) {
		return handover.getParticipants().stream()
				.filter(p -> p.getRole() == role)
				.map(HandoverParticipant::getUserId)
				.toList();
	}
}
