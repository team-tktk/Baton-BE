package com.baton.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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

import com.baton.ai.RagIngestService;
import com.baton.ai.SourceDocument;
import com.baton.ai.SourceDocumentRepository;
import com.baton.ai.SourceDocumentStatus;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.masking.MaskingDetector.DetectedCandidate;
import com.baton.masking.dto.ManualCandidateRequest;
import com.baton.masking.dto.MaskingCandidateResponse;
import com.baton.masking.dto.MaskingReviewResponse;

@ExtendWith(MockitoExtension.class)
class MaskingServiceTest {

	// 이메일은 자동 마스킹, 계좌번호는 줄에 단서가 없어 확인 필요, 이름(4~7)은 탐지되지 않음
	private static final String TEXT = "담당자 김민지 과장\n메일 minji.kim@example.com\n참고 110-123-456789";
	private static final int NAME_START = 4;
	private static final int NAME_END = 7;

	@Mock
	private SourceDocumentRepository sourceDocumentRepository;
	@Mock
	private MaskingCandidateRepository maskingCandidateRepository;
	@Mock
	private MaskingConfirmation maskingConfirmation;
	@Mock
	private RagIngestService ragIngestService;

	private MaskingService service;
	private UUID handoverId;
	private UUID fileId;
	private SourceDocument document;
	private MaskingCandidate email;
	private MaskingCandidate account;

	@BeforeEach
	void setUp() {
		service = new MaskingService(sourceDocumentRepository, maskingCandidateRepository, maskingConfirmation, ragIngestService);
		handoverId = UUID.randomUUID();

		document = SourceDocument.create(handoverId, "contract.docx", "application/zip", 100, "s3-key");
		fileId = UUID.randomUUID();
		ReflectionTestUtils.setField(document, "id", fileId);
		document.markMaskingReview(TEXT);

		List<DetectedCandidate> detected = new MaskingDetector().detect(TEXT);
		email = withId(MaskingCandidate.detected(fileId, handoverId, detected.get(0), TEXT));
		account = withId(MaskingCandidate.detected(fileId, handoverId, detected.get(1), TEXT));

		lenient().when(sourceDocumentRepository.findById(fileId)).thenReturn(Optional.of(document));
	}

	@Test
	void reviewShowsTextAndSummaryWhileInReview() {
		givenCandidates(email, account);

		MaskingReviewResponse response = service.getReview(handoverId, fileId);

		assertThat(response.text()).isEqualTo(TEXT);
		assertThat(response.confirmed()).isFalse();
		assertThat(response.summary().total()).isEqualTo(2);
		assertThat(response.summary().autoMasked()).isEqualTo(1);
		assertThat(response.summary().needsReview()).isEqualTo(1);
		assertThat(response.summary().remaining()).isEqualTo(1);
		assertThat(response.candidates()).extracting(MaskingCandidateResponse::type)
				.containsExactly("EMAIL", "ACCOUNT");
	}

	@Test
	void reviewHidesTextOutsideReview() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.INDEXED);
		givenCandidates(email);

		assertThat(service.getReview(handoverId, fileId).text()).isNull();
	}

	@Test
	void fileOfAnotherHandoverIsNotFound() {
		assertError(() -> service.getReview(UUID.randomUUID(), fileId), ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND);
	}

	@Test
	void decideReleasesCandidateAndMarksReviewed() {
		when(maskingCandidateRepository.findById(account.getId())).thenReturn(Optional.of(account));

		MaskingCandidateResponse response = service.decide(handoverId, fileId, account.getId(), false);

		assertThat(response.applied()).isFalse();
		assertThat(response.pendingReview()).isFalse();
		assertThat(account.isReviewed()).isTrue();
	}

	@Test
	void cannotChangeCandidatesOutsideReview() {
		ReflectionTestUtils.setField(document, "status", SourceDocumentStatus.INDEXED);

		assertError(() -> service.decide(handoverId, fileId, account.getId(), false), ErrorCode.MASKING_NOT_IN_REVIEW);
		assertError(() -> service.addManual(handoverId, fileId, manual(NAME_START, NAME_END)),
				ErrorCode.MASKING_NOT_IN_REVIEW);
		assertError(() -> service.deleteManual(handoverId, fileId, account.getId()), ErrorCode.MASKING_NOT_IN_REVIEW);
	}

	@Test
	void candidateOfAnotherFileIsNotFound() {
		MaskingCandidate other = withId(MaskingCandidate.manual(UUID.randomUUID(), handoverId, MaskingType.CUSTOM, 0, 3, TEXT));
		when(maskingCandidateRepository.findById(other.getId())).thenReturn(Optional.of(other));

		assertError(() -> service.decide(handoverId, fileId, other.getId(), true), ErrorCode.MASKING_CANDIDATE_NOT_FOUND);
	}

	@Test
	void addManualSavesReviewedCustomCandidate() {
		givenCandidates(email, account);
		when(maskingCandidateRepository.save(any(MaskingCandidate.class))).thenAnswer(inv -> inv.getArgument(0));

		MaskingCandidateResponse response = service.addManual(handoverId, fileId, manual(NAME_START, NAME_END));

		assertThat(response.type()).isEqualTo("CUSTOM");
		assertThat(response.origin()).isEqualTo("MANUAL");
		assertThat(response.applied()).isTrue();
		assertThat(response.pendingReview()).isFalse();
		assertThat(response.preview()).isEqualTo("김**");
	}

	@Test
	void addManualRejectsInvalidRanges() {
		assertError(() -> service.addManual(handoverId, fileId, manual(0, TEXT.length() + 1)),
				ErrorCode.MASKING_INVALID_RANGE);
		assertError(() -> service.addManual(handoverId, fileId, manual(5, 5)), ErrorCode.MASKING_INVALID_RANGE);
		assertError(() -> service.addManual(handoverId, fileId, manual(3, 4)), ErrorCode.MASKING_INVALID_RANGE);

		verify(maskingCandidateRepository, never()).save(any());
	}

	@Test
	void addManualRejectsOverlapWithExistingCandidate() {
		givenCandidates(email, account);

		assertError(() -> service.addManual(handoverId, fileId, manual(email.getStartOffset() - 1, email.getStartOffset() + 1)),
				ErrorCode.MASKING_RANGE_OVERLAP);
		verify(maskingCandidateRepository, never()).save(any());
	}

	@Test
	void detectedCandidateCannotBeDeleted() {
		when(maskingCandidateRepository.findById(email.getId())).thenReturn(Optional.of(email));

		assertError(() -> service.deleteManual(handoverId, fileId, email.getId()),
				ErrorCode.MASKING_CANDIDATE_NOT_DELETABLE);
		verify(maskingCandidateRepository, never()).delete(any());
	}

	@Test
	void manualCandidateCanBeDeleted() {
		MaskingCandidate name = withId(MaskingCandidate.manual(fileId, handoverId, MaskingType.CUSTOM, NAME_START, NAME_END, TEXT));
		when(maskingCandidateRepository.findById(name.getId())).thenReturn(Optional.of(name));

		service.deleteManual(handoverId, fileId, name.getId());

		verify(maskingCandidateRepository).delete(name);
	}

	@Test
	void confirmAppliesMaskingBeforeIndexing() {
		service.confirm(handoverId, fileId);

		InOrder order = inOrder(maskingConfirmation, ragIngestService);
		order.verify(maskingConfirmation).apply(handoverId, fileId);
		order.verify(ragIngestService).indexConfirmed(handoverId, fileId);
	}

	@Test
	void confirmDoesNotIndexWhenConfirmationFails() {
		doThrow(new BusinessException(ErrorCode.MASKING_REVIEW_INCOMPLETE))
				.when(maskingConfirmation).apply(handoverId, fileId);

		assertError(() -> service.confirm(handoverId, fileId), ErrorCode.MASKING_REVIEW_INCOMPLETE);
		verify(ragIngestService, never()).indexConfirmed(any(), any());
	}

	@Test
	void pendingCountsAreMappedByFile() {
		UUID otherFile = UUID.randomUUID();
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[] {fileId, 2L});
		rows.add(new Object[] {otherFile, 1L});
		when(maskingCandidateRepository.countPendingReviewGroupedBySourceDocument(handoverId)).thenReturn(rows);

		assertThat(service.countPendingReviewByFile(handoverId))
				.containsEntry(fileId, 2L)
				.containsEntry(otherFile, 1L);
	}

	private void givenCandidates(MaskingCandidate... candidates) {
		when(maskingCandidateRepository.findAllBySourceDocumentIdOrderByStartOffset(fileId))
				.thenReturn(List.of(candidates));
	}

	private static ManualCandidateRequest manual(int start, int end) {
		return new ManualCandidateRequest(start, end, null);
	}

	private static MaskingCandidate withId(MaskingCandidate candidate) {
		ReflectionTestUtils.setField(candidate, "id", UUID.randomUUID());
		return candidate;
	}

	private static void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run)
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}
}
