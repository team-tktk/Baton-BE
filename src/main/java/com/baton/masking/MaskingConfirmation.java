package com.baton.masking;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.LargeObjectCleaner;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.SourceDocumentStatus;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 마스킹 검수 확정의 DB 처리. 원문을 마스킹된 텍스트로 바꾸고 원문 Large Object를 지운 뒤 커밋한다.
 * 임베딩(OpenAI 호출)은 오래 걸리므로 이 트랜잭션이 끝난 뒤 따로 한다(MaskingService.confirm).
 *
 * 파일 행을 쓰기 잠금으로 읽어서, 확정이 동시에 두 번 들어와도 두 번째는 상태 검사에서 막힌다.
 */
@Component
@RequiredArgsConstructor
public class MaskingConfirmation {

	private final SourceDocumentRepository sourceDocumentRepository;
	private final MaskingCandidateRepository maskingCandidateRepository;
	private final LargeObjectCleaner largeObjectCleaner;

	@Transactional
	public void apply(UUID handoverId, UUID fileId) {
		SourceDocument document = sourceDocumentRepository.findByIdForUpdate(fileId)
				.filter(doc -> doc.getHandoverId().equals(handoverId))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
		if (document.getStatus() != SourceDocumentStatus.MASKING_REVIEW) {
			throw new BusinessException(ErrorCode.MASKING_NOT_IN_REVIEW);
		}

		List<MaskingCandidate> candidates = maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId);
		long remaining = candidates.stream().filter(MaskingCandidate::isPendingReview).count();
		if (remaining > 0) {
			throw new BusinessException(ErrorCode.MASKING_REVIEW_INCOMPLETE,
					"확인하지 않은 마스킹 항목이 %d개 있습니다.".formatted(remaining));
		}

		Long rawTextOid = largeObjectCleaner.extractedTextOid(fileId);
		document.confirmMasking(MaskingApplier.apply(document.getExtractedText(), candidates));
		sourceDocumentRepository.saveAndFlush(document);
		largeObjectCleaner.unlinkIfReplaced(fileId, rawTextOid);
	}
}
