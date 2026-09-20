package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.ai.dto.HandoverDraftContent;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagSourceVersionServiceTest {
	@Mock RagSourceVersionRepository repository;
	private RagSourceVersionService service;

	@BeforeEach
	void setUp() {
		service = new RagSourceVersionService(repository, new ObjectMapper());
	}

	@Test
	void archivesOnlyIndexedSourceTextThatWasUsedForSearch() {
		UUID handoverId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		SourceDocument source = SourceDocument.createExternal(handoverId, SourceType.SLACK_MESSAGE,
				"쿠폰 담당자", null, "https://baton.slack.com/archives/C1/p1", "마케팅팀", null, true);
		ReflectionTestUtils.setField(source, "id", sourceId);
		source.markIndexed("최종 담당자는 김민성입니다.", List.of("chunk-1"));
		when(repository.existsBySourceIdAndContentHash(any(), any())).thenReturn(false);

		service.archive(source);

		ArgumentCaptor<RagSourceVersion> captor = ArgumentCaptor.forClass(RagSourceVersion.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getHandoverId()).isEqualTo(handoverId);
		assertThat(captor.getValue().getSourceId()).isEqualTo(sourceId);
		assertThat(captor.getValue().getSourceType()).isEqualTo("SLACK_MESSAGE");
		assertThat(captor.getValue().getLocator()).isEqualTo("https://baton.slack.com/archives/C1/p1");
		assertThat(captor.getValue().getContent()).isEqualTo("최종 담당자는 김민성입니다.");
	}

	@Test
	void doesNotArchiveRawTextWaitingForMaskingReview() {
		SourceDocument source = SourceDocument.create(UUID.randomUUID(), "계약서.pdf", "application/pdf", 100, "key");
		ReflectionTestUtils.setField(source, "id", UUID.randomUUID());
		source.markMaskingReview("이메일 user@example.com");

		service.archive(source);

		verify(repository, never()).save(any());
	}

	@Test
	void draftVersionsIncludeRevisionAndStructuredContent() {
		UUID sourceId = UUID.randomUUID();
		HandoverDraftContent content = new HandoverDraftContent("목적", "완료 기준", List.of(), List.of(),
				List.of("예외 규칙"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		HandoverDraft draft = HandoverDraft.create(UUID.randomUUID(), content);
		ReflectionTestUtils.setField(draft, "id", sourceId);
		when(repository.existsBySourceIdAndContentHash(any(), any())).thenReturn(false);

		service.archive(draft);

		ArgumentCaptor<RagSourceVersion> captor = ArgumentCaptor.forClass(RagSourceVersion.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getSourceType()).isEqualTo("HANDOVER_DRAFT");
		assertThat(captor.getValue().getSourceRevision()).isZero();
		assertThat(captor.getValue().getContent()).contains("\"purpose\":\"목적\"", "\"rulesAndExceptions\":[\"예외 규칙\"]");
	}

	@Test
	void historyLookupScopesByHandoverAndSourceAndCapsResponseSize() {
		UUID handoverId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		when(repository.findAllByHandoverIdAndSourceIdOrderByArchivedAtDesc(
				any(), any(), any(Pageable.class))).thenReturn(List.of());

		service.list(handoverId, sourceId, 3, 1_000);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(repository).findAllByHandoverIdAndSourceIdOrderByArchivedAtDesc(
				org.mockito.ArgumentMatchers.eq(handoverId), org.mockito.ArgumentMatchers.eq(sourceId), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
	}
}
