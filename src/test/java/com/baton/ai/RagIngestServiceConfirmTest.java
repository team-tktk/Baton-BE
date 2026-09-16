package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.HandoverRepository;
import com.baton.masking.MaskingCandidateRepository;
import com.baton.masking.MaskingDetector;

import jakarta.persistence.EntityManager;

/** 마스킹 검수 확정 이후의 흐름: 마스킹된 텍스트 임베딩, 확정된 파일의 재처리, 파일 삭제 시 원문 정리. */
@ExtendWith(MockitoExtension.class)
class RagIngestServiceConfirmTest {

	private static final String MASKED_TEXT = "담당자 이메일 : [이메일#1]";

	@Mock
	private SourceDocumentRepository sourceDocumentRepository;
	@Mock
	private SourceDocumentPersistence sourceDocumentPersistence;
	@Mock
	private VectorStore vectorStore;
	@Mock
	private S3FileStorage s3FileStorage;
	@Mock
	private EntityManager entityManager;
	@Mock
	private FileSignatureValidator fileSignatureValidator;
	@Mock
	private HandoverRepository handoverRepository;
	@Mock
	private MaskingCandidateRepository maskingCandidateRepository;
	@Mock
	private LargeObjectCleaner largeObjectCleaner;

	@Captor
	private ArgumentCaptor<List<Document>> chunksCaptor;

	private RagIngestService service;
	private UUID handoverId;
	private UUID fileId;
	private SourceDocument document;

	@BeforeEach
	void setUp() {
		service = new RagIngestService(sourceDocumentRepository, sourceDocumentPersistence,
				vectorStore, TokenTextSplitter.builder().build(), s3FileStorage, entityManager, fileSignatureValidator,
				handoverRepository, new MaskingDetector(), maskingCandidateRepository, largeObjectCleaner);
		handoverId = UUID.randomUUID();
		fileId = UUID.randomUUID();
		document = SourceDocument.create(handoverId, "contract.docx", "application/zip", 100, "s3-key");
		ReflectionTestUtils.setField(document, "id", fileId);
	}

	@Test
	void indexConfirmedEmbedsOnlyMaskedText() {
		when(sourceDocumentPersistence.readForIndexing(fileId))
				.thenReturn(new SourceDocumentPersistence.IndexingSource("contract.docx", MASKED_TEXT));

		service.indexConfirmed(handoverId, fileId);

		verify(vectorStore).add(chunksCaptor.capture());
		List<Document> chunks = chunksCaptor.getValue();
		assertThat(chunks).isNotEmpty();
		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(chunk.getText()).doesNotContain("@");
			assertThat(chunk.getMetadata())
					.containsEntry("handoverId", handoverId.toString())
					.containsEntry("sourceDocumentId", fileId.toString())
					.containsEntry("fileName", "contract.docx");
		});
		verify(sourceDocumentPersistence).markIndexed(fileId, chunks.stream().map(Document::getId).toList());
		verify(sourceDocumentPersistence, never()).markFailed(any());
	}

	@Test
	void indexConfirmedFailureMarksFileFailed() {
		when(sourceDocumentPersistence.readForIndexing(fileId))
				.thenReturn(new SourceDocumentPersistence.IndexingSource("contract.docx", MASKED_TEXT));
		doThrow(new RuntimeException("openai down")).when(vectorStore).add(anyList());

		assertThatThrownBy(() -> service.indexConfirmed(handoverId, fileId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_FILE_PARSE_FAILED);

		verify(sourceDocumentPersistence).markFailed(fileId);
		verify(sourceDocumentPersistence, never()).markIndexed(any(), anyList());
	}

	@Test
	void retryOfConfirmedFileReindexesWithoutDownloadingOriginal() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.FAILED);
		ReflectionTestUtils.setField(document, "maskingConfirmedAt", Instant.now());
		when(sourceDocumentRepository.findById(fileId)).thenReturn(Optional.of(document));
		when(sourceDocumentPersistence.readForIndexing(fileId))
				.thenReturn(new SourceDocumentPersistence.IndexingSource("contract.docx", MASKED_TEXT));

		service.retry(handoverId, fileId);

		InOrder order = inOrder(sourceDocumentPersistence, vectorStore);
		order.verify(sourceDocumentPersistence).markIndexing(fileId);
		order.verify(vectorStore).add(anyList());
		order.verify(sourceDocumentPersistence).markIndexed(any(UUID.class), anyList());
		verify(s3FileStorage, never()).download(any());
		verify(sourceDocumentPersistence, never()).markMaskingReview(any(), any(), any());
	}

	@Test
	void deleteRemovesRawTextLargeObject() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.MASKING_REVIEW);
		when(sourceDocumentRepository.findById(fileId)).thenReturn(Optional.of(document));
		when(largeObjectCleaner.extractedTextOid(fileId)).thenReturn(777L);

		service.delete(handoverId, fileId);

		InOrder order = inOrder(sourceDocumentRepository, largeObjectCleaner);
		order.verify(sourceDocumentRepository).delete(document);
		order.verify(sourceDocumentRepository).flush();
		order.verify(largeObjectCleaner).unlink(777L);
		verify(maskingCandidateRepository).deleteAllBySourceDocumentId(fileId);
	}

	@Test
	void deleteIsBlockedWhileIndexing() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.INDEXING);
		when(sourceDocumentRepository.findById(fileId)).thenReturn(Optional.of(document));

		assertThatThrownBy(() -> service.delete(handoverId, fileId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_SOURCE_DOCUMENT_PROCESSING);
		verify(largeObjectCleaner, never()).unlink(any());
	}
}
