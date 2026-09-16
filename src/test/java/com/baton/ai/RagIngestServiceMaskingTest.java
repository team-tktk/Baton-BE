package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.handover.Handover;
import com.baton.handover.HandoverRepository;
import com.baton.masking.MaskingCandidate;
import com.baton.masking.MaskingCandidateRepository;
import com.baton.masking.MaskingDetector;
import com.baton.masking.MaskingType;

import jakarta.persistence.EntityManager;

/**
 * 마스킹 검수 스위치(app.masking.enabled)에 따른 업로드 흐름.
 * 꺼져 있으면 기존과 똑같이 임베딩하고, 켜져 있으면 임베딩(= 원문 외부 전송) 없이 검수 대기로 멈춰야 한다.
 */
@ExtendWith(MockitoExtension.class)
class RagIngestServiceMaskingTest {

	private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
	private static final String TEXT = "담당자 이메일 : minji.kim@example.com";

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

	@Captor
	private ArgumentCaptor<List<MaskingCandidate>> candidatesCaptor;
	@Captor
	private ArgumentCaptor<String> textCaptor;

	private RagIngestService service;
	private UUID handoverId;
	private SourceDocument sourceDocument;
	private MockMultipartFile file;

	@BeforeEach
	void setUp() throws IOException {
		service = new RagIngestService(sourceDocumentRepository, sourceDocumentPersistence,
				vectorStore, new TokenTextSplitter(), s3FileStorage, entityManager, fileSignatureValidator,
				handoverRepository, new MaskingDetector(), maskingCandidateRepository);
		ReflectionTestUtils.setField(service, "maxFilesPerHandover", 30);
		ReflectionTestUtils.setField(service, "maxTotalSizePerHandoverMb", 300L);
		ReflectionTestUtils.setField(service, "maxTotalSizePerAccountMb", 1024L);

		handoverId = UUID.randomUUID();
		sourceDocument = SourceDocument.create(handoverId, "contract.docx", DOCX_MIME, 100, "s3-key");
		ReflectionTestUtils.setField(sourceDocument, "id", UUID.randomUUID());
		file = new MockMultipartFile("file", "contract.docx", DOCX_MIME, docxWithText(TEXT));

		when(handoverRepository.findById(handoverId))
				.thenReturn(Optional.of(Handover.create(UUID.randomUUID(), "테스트 인수인계")));
		when(s3FileStorage.upload(any(), any(), any(), any())).thenReturn("s3-key");
		when(sourceDocumentPersistence.createInitial(any(), any(), any(), any(Long.class), any()))
				.thenReturn(sourceDocument);
		when(sourceDocumentRepository.findById(sourceDocument.getId())).thenReturn(Optional.of(sourceDocument));
	}

	@Test
	void whenMaskingDisabledIndexesImmediatelyLikeBefore() {
		ReflectionTestUtils.setField(service, "maskingEnabled", false);

		service.ingest(handoverId, file);

		verify(vectorStore).add(anyList());
		verify(sourceDocumentPersistence).markIndexed(eq(sourceDocument.getId()), anyString(), anyList());
		verify(sourceDocumentPersistence, never()).markMaskingReview(any(), any(), any());
	}

	@Test
	void whenMaskingEnabledHoldsForReviewWithoutEmbedding() {
		ReflectionTestUtils.setField(service, "maskingEnabled", true);

		service.ingest(handoverId, file);

		verify(vectorStore, never()).add(anyList());
		verify(sourceDocumentPersistence, never()).markIndexed(any(), any(), any());
		verify(sourceDocumentPersistence).markMaskingReview(
				eq(sourceDocument.getId()), textCaptor.capture(), candidatesCaptor.capture());

		// Tika는 문단 끝에 줄바꿈을 붙이므로 앞뒤 공백은 비교에서 뺀다. offset은 실제 저장되는 텍스트 기준이다.
		String extractedText = textCaptor.getValue();
		assertThat(extractedText.strip()).isEqualTo(TEXT);

		List<MaskingCandidate> candidates = candidatesCaptor.getValue();
		assertThat(candidates).hasSize(1);
		MaskingCandidate email = candidates.get(0);
		assertThat(email.getType()).isEqualTo(MaskingType.EMAIL);
		assertThat(email.getSourceDocumentId()).isEqualTo(sourceDocument.getId());
		assertThat(email.getHandoverId()).isEqualTo(handoverId);
		assertThat(extractedText.substring(email.getStartOffset(), email.getEndOffset()))
				.isEqualTo("minji.kim@example.com");
		assertThat(email.getPreview()).isEqualTo("min***@example.com");
	}

	/** Tika가 읽을 수 있는 최소 구성의 DOCX(문단 하나). */
	private static byte[] docxWithText(String text) throws IOException {
		Map<String, String> entries = Map.of(
				"[Content_Types].xml", """
						<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
						<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
						<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
						<Default Extension="xml" ContentType="application/xml"/>
						<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
						</Types>""",
				"_rels/.rels", """
						<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
						<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
						<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
						</Relationships>""",
				"word/document.xml", """
						<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
						<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
						<w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
						</w:document>""".formatted(text));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}
}
