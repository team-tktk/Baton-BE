package com.baton.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.baton.ai.dto.DownloadedFile;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;

import jakarta.persistence.EntityManager;
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

	/** 프론트/기획서에서 안내하는 지원 형식(PDF, DOCX, XLSX, PPTX)만 받는다. */
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "xlsx", "pptx");

	/**
	 * 클라이언트가 보낸 Content-Type이 확장자와 명백히 안 맞으면 걸러낸다. 다만 브라우저/클라이언트가
	 * 신뢰할 수 없는 값을 보내는 경우가 흔해서(특히 OOXML), 여기서 통과해도 실제 방어는
	 * FileSignatureValidator의 매직바이트 검증이 한다 — Content-Type이 없거나 애매하면 그쪽에 맡긴다.
	 */
	private static final Map<String, Set<String>> ALLOWED_MIME_TYPES = Map.of(
			"pdf", Set.of("application/pdf"),
			"docx", Set.of(
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document",
					"application/octet-stream", "application/zip"),
			"xlsx", Set.of(
					"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
					"application/octet-stream", "application/zip"),
			"pptx", Set.of(
					"application/vnd.openxmlformats-officedocument.presentationml.presentation",
					"application/octet-stream", "application/zip"));

	private final SourceDocumentRepository sourceDocumentRepository;
	private final SourceDocumentPersistence sourceDocumentPersistence;
	private final VectorStore vectorStore;
	private final TokenTextSplitter tokenTextSplitter;
	private final S3FileStorage s3FileStorage;
	private final EntityManager entityManager;
	private final FileSignatureValidator fileSignatureValidator;
	private final HandoverRepository handoverRepository;

	@Value("${app.upload.max-files-per-handover}")
	private int maxFilesPerHandover;

	@Value("${app.upload.max-total-size-per-handover-mb}")
	private long maxTotalSizePerHandoverMb;

	@Value("${app.upload.max-total-size-per-account-mb}")
	private long maxTotalSizePerAccountMb;

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
		String extension = validateExtension(file.getOriginalFilename());
		validateQuota(handoverId, file.getSize());
		String safeFileName = sanitizeFileName(file.getOriginalFilename());

		byte[] fileBytes;
		try {
			fileBytes = file.getBytes();
		} catch (Exception e) {
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED);
		}

		validateMimeType(extension, file.getContentType());
		fileSignatureValidator.validate(extension, fileBytes);

		String s3Key = s3FileStorage.upload(handoverId, safeFileName, file.getContentType(), fileBytes);

		SourceDocument sourceDocument = sourceDocumentPersistence.createInitial(
				handoverId, safeFileName, file.getContentType(), file.getSize(), s3Key);

		runPipeline(handoverId, sourceDocument, fileBytes);
		return refreshed(sourceDocument);
	}

	/** 추출/임베딩 실패한 파일을 S3에 저장된 원본으로 다시 처리한다. */
	public SourceDocument retry(UUID handoverId, UUID fileId) {
		SourceDocument sourceDocument = findOwned(handoverId, fileId);
		if (sourceDocument.getStatus() != SourceDocumentStatus.FAILED) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "실패한 파일만 재처리할 수 있습니다.");
		}

		byte[] fileBytes = s3FileStorage.download(sourceDocument.getS3Key());
		runPipeline(handoverId, sourceDocument, fileBytes);
		return refreshed(sourceDocument);
	}

	/**
	 * createInitial/markIndexed는 REQUIRES_NEW(별도 트랜잭션·별도 영속성 컨텍스트)로 커밋된다.
	 * ingest()가 들고 있는 sourceDocument는 그 커밋을 반영 못한 detached 인스턴스이고,
	 * retry()가 들고 있는 sourceDocument는 findOwned()로 이미 현재 영속성 컨텍스트에 managed로 붙어있어
	 * 그냥 다시 findById해도 1차 캐시가 같은(오래된) 인스턴스를 그대로 돌려준다.
	 * 두 경우 다 detach로 캐시에서 떼어낸 뒤 다시 조회해야 DB의 진짜 최종 상태를 읽어온다.
	 * (실패 시엔 runPipeline이 예외를 던져 이 지점에 도달하지 않는다.)
	 */
	private SourceDocument refreshed(SourceDocument sourceDocument) {
		entityManager.detach(sourceDocument);
		return sourceDocumentRepository.findById(sourceDocument.getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
	}

	private String validateExtension(String fileName) {
		String extension = extensionOf(fileName);
		if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
			throw new BusinessException(ErrorCode.AI_UNSUPPORTED_FILE_TYPE,
					"PDF, DOCX, XLSX, PPTX 파일만 업로드할 수 있습니다.");
		}
		return extension;
	}

	/**
	 * 인수인계 1건당, 그리고 계정(인계자) 전체 기준 파일 개수·누적 용량 상한을 체크한다.
	 * 파일 1개당 50MB 제한과는 별개로, S3/임베딩 비용이 무제한으로 쌓이는 걸 막기 위함.
	 * 파일을 지우면 그만큼 다시 풀리는 구조라(하드 삭제), 계정이 영구히 막히지는 않는다.
	 */
	private void validateQuota(UUID handoverId, long newFileSize) {
		long currentCount = sourceDocumentRepository.countByHandoverId(handoverId);
		if (currentCount >= maxFilesPerHandover) {
			throw new BusinessException(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED,
					"이 인수인계에는 파일을 최대 %d개까지 업로드할 수 있습니다.".formatted(maxFilesPerHandover));
		}

		long maxHandoverSizeBytes = maxTotalSizePerHandoverMb * 1024 * 1024;
		long currentHandoverSize = sourceDocumentRepository.sumFileSizeByHandoverId(handoverId);
		if (currentHandoverSize + newFileSize > maxHandoverSizeBytes) {
			throw new BusinessException(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED,
					"이 인수인계의 업로드 총 용량은 최대 %dMB까지 가능합니다.".formatted(maxTotalSizePerHandoverMb));
		}

		Handover handover = handoverRepository.findById(handoverId)
				.orElseThrow(() -> new BusinessException(ErrorCode.HANDOVER_NOT_FOUND));
		long maxAccountSizeBytes = maxTotalSizePerAccountMb * 1024 * 1024;
		long currentAccountSize = sourceDocumentRepository.sumFileSizeByOwnerId(handover.getOwnerId());
		if (currentAccountSize + newFileSize > maxAccountSizeBytes) {
			throw new BusinessException(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED,
					"계정 전체 업로드 총 용량은 최대 %dMB까지 가능합니다.".formatted(maxTotalSizePerAccountMb));
		}
	}

	/**
	 * 클라이언트가 보낸 Content-Type이 확장자와 명백히 다르면 거절한다. 값이 없거나(null/blank)
	 * 애매한 경우는 여기서 판단하지 않고 통과시킨다 — 진짜 판단은 매직바이트 검증이 한다.
	 */
	private void validateMimeType(String extension, String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return;
		}
		Set<String> allowed = ALLOWED_MIME_TYPES.get(extension);
		String normalized = contentType.split(";")[0].trim().toLowerCase();
		if (allowed != null && !allowed.contains(normalized)) {
			throw new BusinessException(ErrorCode.AI_UNSUPPORTED_FILE_TYPE,
					"요청한 파일 형식(%s)이 %s 확장자와 맞지 않습니다.".formatted(contentType, extension.toUpperCase()));
		}
	}

	/**
	 * 경로 구분자·상위 디렉토리 참조(..)·제어문자를 제거해서, 파일명이 S3 저장 키나 로그에
	 * 그대로 들어가도 안전하게 만든다. 정상적인 한글/영문 파일명은 그대로 통과한다.
	 */
	private String sanitizeFileName(String rawFileName) {
		if (rawFileName == null || rawFileName.isBlank()) {
			return "file";
		}
		String name = rawFileName.replace('\\', '/');
		name = name.substring(name.lastIndexOf('/') + 1);
		name = name.replaceAll("\\p{Cntrl}", "");
		name = name.replace("..", "_");
		return name.isBlank() ? "file" : name;
	}

	private String extensionOf(String fileName) {
		if (fileName == null) {
			return null;
		}
		int dot = fileName.lastIndexOf('.');
		if (dot < 0 || dot == fileName.length() - 1) {
			return null;
		}
		return fileName.substring(dot + 1).toLowerCase();
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
			List<String> chunkIds = chunks.stream().map(Document::getId).toList();
			sourceDocumentPersistence.markIndexed(sourceDocument.getId(), extractedText, chunkIds);
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
		if (sourceDocument.getStatus() == SourceDocumentStatus.EXTRACTING) {
			throw new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_PROCESSING);
		}

		if (sourceDocument.getChunkIds() != null && !sourceDocument.getChunkIds().isEmpty()) {
			vectorStore.delete(sourceDocument.getChunkIds());
		}
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
