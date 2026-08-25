package com.baton.ai;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baton.ai.dto.DownloadedFile;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 업로드된 파일을 텍스트로 추출하고 청크 단위로 쪼개 벡터스토어에 저장한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagIngestService {

	private static final String META_HANDOVER_ID = "handoverId";
	private static final String META_SOURCE_DOCUMENT_ID = "sourceDocumentId";
	private static final String META_FILE_NAME = "fileName";
	private static final String META_CHUNK_INDEX = "chunkIndex";

	private final SourceDocumentRepository sourceDocumentRepository;
	private final SourceDocumentPersistence sourceDocumentPersistence;
	private final VectorStore vectorStore;
	private final TokenTextSplitter tokenTextSplitter;
	private final S3FileStorage s3FileStorage;

	/**
	 * RagController 클래스 전체에 @Transactional이 걸려있어서(권한 체크의 지연로딩 때문), 여기서
	 * 상태 변경을 직접 save해도 파싱/임베딩 실패 시 컨트롤러 트랜잭션과 함께 롤백돼버린다 —
	 * FAILED 상태가 DB에 영영 안 남아 재처리(retry) 대상 자체가 사라지는 버그였다.
	 * 그래서 생성/상태변경은 SourceDocumentPersistence(REQUIRES_NEW)를 통해 독립적으로 커밋한다.
	 */
	public SourceDocument ingest(UUID handoverId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다.");
		}

		byte[] fileBytes;
		try {
			fileBytes = file.getBytes();
		} catch (Exception e) {
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED);
		}

		String s3Key = s3FileStorage.upload(handoverId, file.getOriginalFilename(), file.getContentType(), fileBytes);

		SourceDocument sourceDocument = sourceDocumentPersistence.createInitial(
				handoverId, file.getOriginalFilename(), file.getContentType(), file.getSize(), s3Key);

		runPipeline(handoverId, sourceDocument, fileBytes);
		return sourceDocument;
	}

	/** 추출/임베딩 실패한 파일을 S3에 저장된 원본으로 다시 처리한다. */
	public SourceDocument retry(UUID handoverId, UUID fileId) {
		SourceDocument sourceDocument = findOwned(handoverId, fileId);
		if (sourceDocument.getStatus() != SourceDocumentStatus.FAILED) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "실패한 파일만 재처리할 수 있습니다.");
		}

		byte[] fileBytes = s3FileStorage.download(sourceDocument.getS3Key());
		runPipeline(handoverId, sourceDocument, fileBytes);
		return sourceDocument;
	}

	private void runPipeline(UUID handoverId, SourceDocument sourceDocument, byte[] fileBytes) {
		try {
			List<Document> rawDocuments = extractText(fileBytes, sourceDocument.getFileName());
			String extractedText = rawDocuments.stream()
					.map(Document::getText)
					.collect(Collectors.joining("\n\n"));

			List<Document> chunks = tokenTextSplitter.apply(rawDocuments);
			attachMetadata(chunks, handoverId, sourceDocument);

			vectorStore.add(chunks);
			sourceDocumentPersistence.markIndexed(sourceDocument.getId(), extractedText);
		} catch (BusinessException e) {
			sourceDocumentPersistence.markFailed(sourceDocument.getId());
			throw e;
		} catch (Exception e) {
			log.error("[*] RAG ingest failed for sourceDocumentId={}", sourceDocument.getId(), e);
			sourceDocumentPersistence.markFailed(sourceDocument.getId());
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED);
		}
	}

	private List<Document> extractText(byte[] fileBytes, String fileName) throws Exception {
		ByteArrayResource resource = new ByteArrayResource(fileBytes) {
			@Override
			public String getFilename() {
				return fileName;
			}
		};

		TikaDocumentReader reader = new TikaDocumentReader(resource);
		List<Document> rawDocuments = reader.get();

		boolean noText = rawDocuments.isEmpty()
				|| rawDocuments.stream().allMatch(d -> d.getText() == null || d.getText().isBlank());
		if (noText) {
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED);
		}

		return rawDocuments;
	}

	private void attachMetadata(List<Document> chunks, UUID handoverId, SourceDocument sourceDocument) {
		for (int i = 0; i < chunks.size(); i++) {
			Document chunk = chunks.get(i);
			chunk.getMetadata().put(META_HANDOVER_ID, handoverId.toString());
			chunk.getMetadata().put(META_SOURCE_DOCUMENT_ID, sourceDocument.getId().toString());
			chunk.getMetadata().put(META_FILE_NAME, sourceDocument.getFileName());
			chunk.getMetadata().put(META_CHUNK_INDEX, i);
		}
	}

	@Transactional(readOnly = true)
	public List<SourceDocument> listByHandover(UUID handoverId) {
		return sourceDocumentRepository.findAllByHandoverId(handoverId);
	}

	/** AI 답변의 근거(citation)를 눌렀을 때 원문 메타데이터를 보여준다. */
	@Transactional(readOnly = true)
	public SourceDocument getSource(UUID handoverId, UUID sourceId) {
		return findOwned(handoverId, sourceId);
	}

	@Transactional(readOnly = true)
	public DownloadedFile download(UUID handoverId, UUID fileId) {
		SourceDocument sourceDocument = findOwned(handoverId, fileId);
		byte[] content = s3FileStorage.download(sourceDocument.getS3Key());
		return new DownloadedFile(sourceDocument.getFileName(), sourceDocument.getMimeType(), content);
	}

	@Transactional
	public void delete(UUID handoverId, UUID fileId) {
		SourceDocument sourceDocument = findOwned(handoverId, fileId);
		s3FileStorage.delete(sourceDocument.getS3Key());
		sourceDocumentRepository.delete(sourceDocument);
	}

	private SourceDocument findOwned(UUID handoverId, UUID fileId) {
		SourceDocument sourceDocument = sourceDocumentRepository.findById(fileId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
		if (!sourceDocument.getHandoverId().equals(handoverId)) {
			throw new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND);
		}
		return sourceDocument;
	}
}
