package com.baton.ai;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourceDocument createInitial(UUID handoverId, String fileName, String mimeType, long fileSize, String s3Key) {
		return sourceDocumentRepository.save(SourceDocument.create(handoverId, fileName, mimeType, fileSize, s3Key));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markIndexed(UUID sourceDocumentId, String extractedText) {
		sourceDocumentRepository.findById(sourceDocumentId)
				.ifPresent(doc -> {
					doc.markIndexed(extractedText);
					sourceDocumentRepository.save(doc);
				});
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
