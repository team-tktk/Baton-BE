package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.ai.LargeObjectCleaner;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.SourceDocumentStatus;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.masking.MaskingDetector.DetectedCandidate;

@ExtendWith(MockitoExtension.class)
class MaskingConfirmationTest {

	private static final String TEXT = "메일 minji.kim@example.com\n참고 110-123-456789";
	private static final long RAW_TEXT_OID = 12345L;

	@Mock
	private SourceDocumentRepository sourceDocumentRepository;
	@Mock
	private MaskingCandidateRepository maskingCandidateRepository;
	@Mock
	private LargeObjectCleaner largeObjectCleaner;

	private MaskingConfirmation confirmation;
	private UUID handoverId;
	private UUID fileId;
	private SourceDocument document;
	private MaskingCandidate email;
	private MaskingCandidate account;

	@BeforeEach
	void setUp() {
		confirmation = new MaskingConfirmation(sourceDocumentRepository, maskingCandidateRepository, largeObjectCleaner);
		handoverId = UUID.randomUUID();
		fileId = UUID.randomUUID();
		document = SourceDocument.create(handoverId, "contract.docx", "application/zip", 100, "s3-key");
		ReflectionTestUtils.setField(document, "id", fileId);
		document.markMaskingReview(TEXT);

		List<DetectedCandidate> detected = new MaskingDetector().detect(TEXT);
		email = MaskingCandidate.detected(fileId, handoverId, detected.get(0), TEXT);
		account = MaskingCandidate.detected(fileId, handoverId, detected.get(1), TEXT);

		when(sourceDocumentRepository.findByIdForUpdate(fileId)).thenReturn(Optional.of(document));
	}

	@Test
	void replacesTextWithMaskedVersionAndRemovesRawText() {
		account.decide(true);
		when(maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId))
				.thenReturn(List.of(email, account));
		when(largeObjectCleaner.extractedTextOid(fileId)).thenReturn(RAW_TEXT_OID);

		confirmation.apply(handoverId, fileId);

		assertThat(document.getExtractedText()).isEqualTo("메일 [이메일#1]\n참고 [계좌번호#1]");
		assertThat(document.getStatus()).isEqualTo(SourceDocumentStatus.INDEXING);
		assertThat(document.isMaskingConfirmed()).isTrue();

		InOrder order = inOrder(sourceDocumentRepository, largeObjectCleaner);
		order.verify(sourceDocumentRepository).saveAndFlush(document);
		order.verify(largeObjectCleaner).unlinkIfReplaced(fileId, RAW_TEXT_OID);
	}

	@Test
	void releasedCandidateIsNotMasked() {
		account.decide(false);
		when(maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId))
				.thenReturn(List.of(email, account));

		confirmation.apply(handoverId, fileId);

		assertThat(document.getExtractedText()).isEqualTo("메일 [이메일#1]\n참고 110-123-456789");
	}

	@Test
	void rejectsWhileReviewIsPending() {
		when(maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId))
				.thenReturn(List.of(email, account));

		assertError(() -> confirmation.apply(handoverId, fileId), ErrorCode.MASKING_REVIEW_INCOMPLETE);

		assertThat(document.getExtractedText()).isEqualTo(TEXT);
		assertThat(document.isMaskingConfirmed()).isFalse();
		verify(sourceDocumentRepository, never()).saveAndFlush(any());
		verify(largeObjectCleaner, never()).unlinkIfReplaced(any(), any());
	}

	@Test
	void rejectsWhenNotInReview() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.INDEXED);

		assertError(() -> confirmation.apply(handoverId, fileId), ErrorCode.MASKING_NOT_IN_REVIEW);
		verify(sourceDocumentRepository, never()).saveAndFlush(any());
	}

	@Test
	void fileOfAnotherHandoverIsNotFound() {
		assertError(() -> confirmation.apply(UUID.randomUUID(), fileId), ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND);
	}

	private static void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}
}
