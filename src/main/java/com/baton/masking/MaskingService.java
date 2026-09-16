package com.baton.masking;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.RagIngestService;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.SourceDocumentStatus;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.masking.dto.ManualCandidateRequest;
import com.baton.masking.dto.MaskingCandidateResponse;
import com.baton.masking.dto.MaskingReviewResponse;

import lombok.RequiredArgsConstructor;

/**
 * 마스킹 검수 화면의 조회·적용/해제·직접 추가/삭제.
 * 수정은 검수 대기(MASKING_REVIEW) 파일에서만 허용한다. 확정 이후에는 원문이 없어 위치(offset)를 검증할 수 없기 때문이다.
 * 파일은 URL의 인수인계에, 후보는 URL의 파일에 속해야 한다. 아니면 존재하지 않는 것으로 응답한다(404).
 */
@Service
@RequiredArgsConstructor
public class MaskingService {

	private final SourceDocumentRepository sourceDocumentRepository;
	private final MaskingCandidateRepository maskingCandidateRepository;
	private final MaskingConfirmation maskingConfirmation;
	private final RagIngestService ragIngestService;

	@Transactional(readOnly = true)
	public MaskingReviewResponse getReview(UUID handoverId, UUID fileId) {
		SourceDocument document = loadDocument(handoverId, fileId);
		return MaskingReviewResponse.of(document, candidatesOf(fileId));
	}

	@Transactional
	public MaskingCandidateResponse decide(UUID handoverId, UUID fileId, UUID candidateId, boolean applied) {
		requireInReview(loadDocument(handoverId, fileId));
		MaskingCandidate candidate = loadCandidate(fileId, candidateId);
		candidate.decide(applied);
		return MaskingCandidateResponse.from(candidate);
	}

	@Transactional
	public MaskingCandidateResponse addManual(UUID handoverId, UUID fileId, ManualCandidateRequest request) {
		SourceDocument document = loadDocument(handoverId, fileId);
		requireInReview(document);

		int start = request.startOffset();
		int end = request.endOffset();
		String text = document.getExtractedText();
		if (text == null || start >= end || end > text.length()) {
			throw new BusinessException(ErrorCode.MASKING_INVALID_RANGE);
		}
		if (text.substring(start, end).isBlank()) {
			throw new BusinessException(ErrorCode.MASKING_INVALID_RANGE, "공백만 있는 구간은 마스킹할 수 없습니다.");
		}
		if (candidatesOf(fileId).stream().anyMatch(c -> c.overlaps(start, end))) {
			throw new BusinessException(ErrorCode.MASKING_RANGE_OVERLAP);
		}

		MaskingCandidate candidate = MaskingCandidate.manual(
				fileId, handoverId, request.typeOrDefault(), start, end, text);
		return MaskingCandidateResponse.from(maskingCandidateRepository.save(candidate));
	}

	/** 직접 추가한 항목만 지울 수 있다. 자동으로 찾은 항목은 체크 해제로 처리한다. */
	@Transactional
	public void deleteManual(UUID handoverId, UUID fileId, UUID candidateId) {
		requireInReview(loadDocument(handoverId, fileId));
		MaskingCandidate candidate = loadCandidate(fileId, candidateId);
		if (candidate.getOrigin() != MaskingOrigin.MANUAL) {
			throw new BusinessException(ErrorCode.MASKING_CANDIDATE_NOT_DELETABLE);
		}
		maskingCandidateRepository.delete(candidate);
	}

	/**
	 * 검수 확정. ① 마스킹 텍스트로 교체·원문 삭제(트랜잭션) → ② 마스킹된 텍스트만 임베딩(트랜잭션 밖).
	 * ②가 실패하면 파일은 FAILED가 되고, 재처리하면 원문 추출 없이 임베딩만 다시 한다.
	 * 트랜잭션을 걸지 않는다 — 오래 걸리는 임베딩 동안 DB 잠금을 잡고 있지 않기 위함.
	 */
	public void confirm(UUID handoverId, UUID fileId) {
		maskingConfirmation.apply(handoverId, fileId);
		ragIngestService.indexConfirmed(handoverId, fileId);
	}

	/** 파일 목록용 — 파일별 남은 확인 개수. 남은 게 없는 파일은 결과에 없다. */
	@Transactional(readOnly = true)
	public Map<UUID, Long> countPendingReviewByFile(UUID handoverId) {
		return maskingCandidateRepository.countPendingReviewGroupedBySourceDocument(handoverId).stream()
				.collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
	}

	private List<MaskingCandidate> candidatesOf(UUID fileId) {
		return maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId);
	}

	private SourceDocument loadDocument(UUID handoverId, UUID fileId) {
		return sourceDocumentRepository.findById(fileId)
				.filter(document -> document.getHandoverId().equals(handoverId))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
	}

	private MaskingCandidate loadCandidate(UUID fileId, UUID candidateId) {
		return maskingCandidateRepository.findById(candidateId)
				.filter(candidate -> candidate.getSourceDocumentId().equals(fileId))
				.orElseThrow(() -> new BusinessException(ErrorCode.MASKING_CANDIDATE_NOT_FOUND));
	}

	private void requireInReview(SourceDocument document) {
		if (document.getStatus() != SourceDocumentStatus.MASKING_REVIEW) {
			throw new BusinessException(ErrorCode.MASKING_NOT_IN_REVIEW);
		}
	}
}
