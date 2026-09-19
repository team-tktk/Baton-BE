package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.baton.masking.MaskingCandidateRepository;
import com.baton.masking.MaskingDetector;

import jakarta.persistence.EntityManager;

/** 인수인계 1건당 업로드 개수·용량 상한 검증. 상한 초과 시 S3 업로드까지 가기 전에 걸러지는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class RagIngestServiceQuotaTest {

	@Mock
	private SourceDocumentRepository sourceDocumentRepository;
	@Mock
	private SourceDocumentPersistence sourceDocumentPersistence;
	@Mock
	private VectorStore vectorStore;
	@Mock
	private TokenTextSplitter tokenTextSplitter;
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

	private RagIngestService service;
	private UUID handoverId;
	private UUID ownerId;

	@BeforeEach
	void setUp() {
		service = new RagIngestService(sourceDocumentRepository, sourceDocumentPersistence,
				vectorStore, tokenTextSplitter, s3FileStorage, entityManager, fileSignatureValidator, handoverRepository,
				new MaskingDetector(), maskingCandidateRepository, largeObjectCleaner);
		ReflectionTestUtils.setField(service, "maxFilesPerHandover", 30);
		ReflectionTestUtils.setField(service, "maxTotalSizePerHandoverMb", 300L);
		ReflectionTestUtils.setField(service, "maxTotalSizePerAccountMb", 1024L);
		handoverId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
	}

	@Test
	void rejectsWhenFileCountQuotaExceeded() {
		when(sourceDocumentRepository.countByHandoverIdAndSourceType(handoverId, SourceType.FILE)).thenReturn(30L);
		MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes());

		assertThatThrownBy(() -> service.ingest(handoverId, file))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED);

		verify(s3FileStorage, never()).upload(any(), any(), any(), any());
	}

	@Test
	void rejectsWhenHandoverTotalSizeQuotaExceeded() {
		when(sourceDocumentRepository.countByHandoverIdAndSourceType(handoverId, SourceType.FILE)).thenReturn(5L);
		when(sourceDocumentRepository.sumFileSizeByHandoverId(handoverId)).thenReturn(299L * 1024 * 1024);
		MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[2 * 1024 * 1024]);

		assertThatThrownBy(() -> service.ingest(handoverId, file))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED);

		verify(s3FileStorage, never()).upload(any(), any(), any(), any());
	}

	@Test
	void rejectsWhenAccountTotalSizeQuotaExceeded() {
		Handover handover = Handover.create(ownerId, "테스트 인수인계");
		when(sourceDocumentRepository.countByHandoverIdAndSourceType(handoverId, SourceType.FILE)).thenReturn(5L);
		when(sourceDocumentRepository.sumFileSizeByHandoverId(handoverId)).thenReturn(0L);
		when(handoverRepository.findById(handoverId)).thenReturn(java.util.Optional.of(handover));
		when(sourceDocumentRepository.sumFileSizeByOwnerId(ownerId)).thenReturn(1023L * 1024 * 1024);
		MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[2 * 1024 * 1024]);

		assertThatThrownBy(() -> service.ingest(handoverId, file))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(ErrorCode.AI_UPLOAD_QUOTA_EXCEEDED);

		verify(s3FileStorage, never()).upload(any(), any(), any(), any());
	}
}
