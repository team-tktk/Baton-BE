package com.baton.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.masking.MaskingCandidate;
import com.baton.masking.MaskingCandidateRepository;

import lombok.RequiredArgsConstructor;

/**
 * SourceDocument의 상태 변경을 컨트롤러/호출자의 트랜잭션과 분리해서 독립적으로 커밋한다.
 *
 * 왜 필요한가: RagController는 클래스 전체에 @Transactional이 걸려있다(권한 체크의 지연로딩 때문).
 * 그 안에서 파싱/임베딩이 실패해 예외가 던져지면 컨트롤러의 트랜잭션이 롤백되면서, 방금 저장한
 * SourceDocument 행과 markFailed() 상태 변경까지 같이 사라진다 — 재처리(retry)할 대상 자체가
 * DB에 안 남는 버그였다. REQUIRES_NEW로 별도 트랜잭션을 떠서, 실패가 나더라도 "실패했다는 사실"만은
 * 확실히 커밋되게 한다.
 */
@Component
@RequiredArgsConstructor
public class SourceDocumentPersistence {

	private final SourceDocumentRepository sourceDocumentRepository;
	private final MaskingCandidateRepository maskingCandidateRepository;
	private final LargeObjectCleaner largeObjectCleaner;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourceDocument createInitial(UUID handoverId, String fileName, String mimeType, long fileSize, String s3Key) {
		return sourceDocumentRepository.save(SourceDocument.create(handoverId, fileName, mimeType, fileSize, s3Key));
	}

	/** 텍스트를 바꾸는 메서드는 이전 원문 Large Object를 지운다(LargeObjectCleaner 참고). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markIndexed(UUID sourceDocumentId, String extractedText, List<String> chunkIds) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					Long previousOid = largeObjectCleaner.extractedTextOid(sourceDocumentId);
					doc.markIndexed(extractedText, chunkIds);
					sourceDocumentRepository.saveAndFlush(doc);
					largeObjectCleaner.unlinkIfReplaced(sourceDocumentId, previousOid);
				});
	}

	/** 확정된 파일의 임베딩 완료. 이미 마스킹된 텍스트는 건드리지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markIndexed(UUID sourceDocumentId, List<String> chunkIds) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					doc.markIndexed(chunkIds);
					sourceDocumentRepository.save(doc);
				});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markIndexing(UUID sourceDocumentId) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					doc.markIndexing();
					sourceDocumentRepository.save(doc);
				});
	}

	/**
	 * 후보 교체와 상태 변경을 한 트랜잭션으로 묶는다. 재처리(retry)로 다시 들어온 경우를 위해
	 * 이전 후보를 먼저 지운다 — 원문이 새로 추출되면 예전 위치(offset)는 의미가 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markMaskingReview(UUID sourceDocumentId, String extractedText, List<MaskingCandidate> candidates) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					Long previousOid = largeObjectCleaner.extractedTextOid(sourceDocumentId);
					maskingCandidateRepository.deleteAllBySourceDocumentId(sourceDocumentId);
					maskingCandidateRepository.saveAll(candidates);
					doc.markMaskingReview(extractedText);
					sourceDocumentRepository.saveAndFlush(doc);
					largeObjectCleaner.unlinkIfReplaced(sourceDocumentId, previousOid);
				});
	}

	/**
	 * Large Object는 트랜잭션 안에서만 읽을 수 있어서, 임베딩에 필요한 값만 트랜잭션 안에서 꺼낸다.
	 * (임베딩은 오래 걸리므로 트랜잭션 밖에서 한다.)
	 */
	@Transactional(readOnly = true)
	public IndexingSource readForIndexing(UUID sourceDocumentId) {
		return sourceDocumentRepository.findById(sourceDocumentId)
				.map(doc -> new IndexingSource(doc.getFileName(), doc.getExtractedText()))
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
	}

	public record IndexingSource(String fileName, String text) {
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(UUID sourceDocumentId) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					doc.markFailed();
					sourceDocumentRepository.save(doc);
				});
	}
}
