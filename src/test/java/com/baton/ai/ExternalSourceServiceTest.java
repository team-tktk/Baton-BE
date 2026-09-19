package com.baton.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.baton.ai.dto.CreateSlackMessageRequest;
import com.baton.common.BusinessException;
import com.baton.masking.MaskingCandidateRepository;

@ExtendWith(MockitoExtension.class)
class ExternalSourceServiceTest {
	@Mock SourceDocumentRepository repository;
	@Mock SourceDocumentPersistence persistence;
	@Mock RagIngestService ragIngestService;
	@Mock SafeWebSourceFetcher webFetcher;
	@Mock MaskingCandidateRepository maskingCandidateRepository;
	@Mock LargeObjectCleaner largeObjectCleaner;
	private ExternalSourceService service;

	@BeforeEach
	void setUp() {
		service = new ExternalSourceService(repository, persistence, ragIngestService, webFetcher,
				maskingCandidateRepository, largeObjectCleaner);
	}

	@Test
	void createsSlackSourceAndSendsPastedTextToCommonPipeline() {
		UUID handoverId = UUID.randomUUID();
		SourceDocument source = SourceDocument.createExternal(handoverId, SourceType.SLACK_MESSAGE,
				"결정", null, "https://baton.slack.com/archives/C1/p1", "#개발", null, true);
		ReflectionTestUtils.setField(source, "id", UUID.randomUUID());
		when(persistence.createExternal(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
				.thenReturn(source);
		when(ragIngestService.ingestText(handoverId, source.getId(), "본문")).thenReturn(source);

		service.createSlack(handoverId, new CreateSlackMessageRequest(
				"https://baton.slack.com/archives/C1/p1", "결정", "#개발", "본문", null, true));

		verify(ragIngestService).ingestText(handoverId, source.getId(), "본문");
	}

	@Test
	void rejectsNonSlackMessageUrl() {
		assertThatThrownBy(() -> service.createSlack(UUID.randomUUID(), new CreateSlackMessageRequest(
				"https://evil.example/messages/1", "결정", "#개발", "본문", null, true)))
				.isInstanceOf(BusinessException.class);
	}
}
