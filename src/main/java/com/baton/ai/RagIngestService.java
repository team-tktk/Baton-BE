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
	private final VectorStore vectorStore;
	private final TokenTextSplitter tokenTextSplitter;
	private final S3FileStorage s3FileStorage;

	@Transactional
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

		SourceDocument sourceDocument = sourceDocumentRepository.save(
				SourceDocument.create(handoverId, file.getOriginalFilename(), file.getContentType(),
						file.getSize(), s3Key));

		try {
			List<Document> rawDocuments = extractText(file);
			String extractedText = rawDocuments.stream()
					.map(Document::getText)
					.collect(Collectors.joining("\n\n"));

			List<Document> chunks = tokenTextSplitter.apply(rawDocuments);
			attachMetadata(chunks, handoverId, sourceDocument);

			vectorStore.add(chunks);
			sourceDocument.markIndexed(extractedText);
		} catch (BusinessException e) {
			sourceDocument.markFailed();
			throw e;
		} catch (Exception e) {
			log.error("[*] RAG ingest failed for sourceDocumentId={}", sourceDocument.getId(), e);
			sourceDocument.markFailed();
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED);
		}

		return sourceDocument;
	}

	private List<Document> extractText(MultipartFile file) throws Exception {
		ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
			@Override
			public String getFilename() {
				return file.getOriginalFilename();
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
