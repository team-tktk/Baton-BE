package com.baton.ai;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.SafeWebSourceFetcher.FetchResult;
import com.baton.ai.dto.CreateSlackMessageRequest;
import com.baton.ai.dto.CreateWebLinkRequest;
import com.baton.ai.dto.UpdateExternalSourceRequest;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;
import com.baton.masking.MaskingCandidateRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExternalSourceService {
	private final SourceDocumentRepository repository;
	private final SourceDocumentPersistence persistence;
	private final RagIngestService ragIngestService;
	private final SafeWebSourceFetcher webFetcher;
	private final MaskingCandidateRepository maskingCandidateRepository;
	private final LargeObjectCleaner largeObjectCleaner;

	public Result createWeb(UUID handoverId, CreateWebLinkRequest request) {
		FetchResult fetched = webFetcher.fetch(request.url());
		String title = firstNonBlank(request.title(), fetched.title(), hostTitle(fetched.finalUrl()));
		String text = fetched.fetched() ? fetched.text() : joinText(title, request.description());
		if (!fetched.fetched() && isBlank(request.description())) {
			throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED,
					"로그인이 필요한 링크는 설명을 입력해야 저장할 수 있습니다.");
		}
		SourceDocument source = persistence.createExternal(handoverId, SourceType.WEB_LINK, title,
				request.description(), fetched.finalUrl(), null, null, enabled(request.enabled()));
		return new Result(ragIngestService.ingestText(handoverId, source.getId(), text), fetched.fetched());
	}

	public Result createSlack(UUID handoverId, CreateSlackMessageRequest request) {
		validateSlackUrl(request.messageUrl());
		SourceDocument source = persistence.createExternal(handoverId, SourceType.SLACK_MESSAGE,
				request.title().trim(), null, request.messageUrl().trim(), request.conversationName().trim(),
				request.occurredAt(), enabled(request.enabled()));
		return new Result(ragIngestService.ingestText(handoverId, source.getId(), request.content()), true);
	}

	public Result update(UUID handoverId, UUID sourceId, UpdateExternalSourceRequest request) {
		SourceDocument source = findExternal(handoverId, sourceId);
		if (onlyEnabledChanged(request)) {
			return new Result(toggleEnabled(handoverId, source, request.enabled()), true);
		}

		if (source.getSourceType() == SourceType.WEB_LINK) {
			String requestedUrl = firstNonBlank(request.url(), source.getOriginalUrl());
			FetchResult fetched = webFetcher.fetch(requestedUrl);
			String title = firstNonBlank(request.title(), fetched.title(), source.getFileName());
			String description = request.description() != null ? request.description() : source.getDescription();
			String text = fetched.fetched() ? fetched.text() : joinText(title, description);
			if (!fetched.fetched() && isBlank(description)) {
				throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_FETCH_FAILED,
						"로그인이 필요한 링크는 설명을 입력해야 저장할 수 있습니다.");
			}
			ragIngestService.deleteIndex(source);
			persistence.updateExternal(sourceId, title, description, fetched.finalUrl(), null, null);
			if (request.enabled() != null) persistence.setEnabled(sourceId, request.enabled());
			return new Result(ragIngestService.ingestText(handoverId, sourceId, text), fetched.fetched());
		}

		if (isBlank(request.content())) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "Slack 자료 수정 시 content를 함께 보내주세요.");
		}
		String url = firstNonBlank(request.url(), source.getOriginalUrl());
		validateSlackUrl(url);
		ragIngestService.deleteIndex(source);
		persistence.updateExternal(sourceId, firstNonBlank(request.title(), source.getFileName()),
				source.getDescription(), url, firstNonBlank(request.conversationName(), source.getConversationName()),
				request.occurredAt() != null ? request.occurredAt() : source.getSourceOccurredAt());
		if (request.enabled() != null) persistence.setEnabled(sourceId, request.enabled());
		return new Result(ragIngestService.ingestText(handoverId, sourceId, request.content()), true);
	}

	private SourceDocument toggleEnabled(UUID handoverId, SourceDocument source, boolean enabled) {
		if (source.isEnabled() == enabled) return source;
		if (!enabled) {
			ragIngestService.deleteIndex(source);
			persistence.setEnabled(source.getId(), false);
			return findExternal(handoverId, source.getId());
		}
		persistence.setEnabled(source.getId(), true);
		if (source.getStatus() == SourceDocumentStatus.INDEXED && !isBlank(source.getExtractedText())) {
			persistence.markIndexing(source.getId());
			ragIngestService.indexConfirmed(handoverId, source.getId());
		}
		return findExternal(handoverId, source.getId());
	}

	private boolean onlyEnabledChanged(UpdateExternalSourceRequest r) {
		return r.enabled() != null && r.title() == null && r.description() == null && r.url() == null
				&& r.conversationName() == null && r.content() == null && r.occurredAt() == null;
	}

	public SourceDocument retry(UUID handoverId, UUID sourceId) {
		SourceDocument source = findExternal(handoverId, sourceId);
		if (source.getStatus() != SourceDocumentStatus.FAILED || isBlank(source.getExtractedText())) {
			throw new BusinessException(ErrorCode.HANDOVER_INVALID_STATE, "저장된 텍스트가 있는 실패 자료만 재처리할 수 있습니다.");
		}
		return source.isMaskingConfirmed()
				? retryConfirmed(handoverId, sourceId)
				: ragIngestService.ingestText(handoverId, sourceId, source.getExtractedText());
	}

	private SourceDocument retryConfirmed(UUID handoverId, UUID sourceId) {
		persistence.markIndexing(sourceId);
		ragIngestService.indexConfirmed(handoverId, sourceId);
		return findExternal(handoverId, sourceId);
	}

	@Transactional
	public void delete(UUID handoverId, UUID sourceId) {
		SourceDocument source = findExternal(handoverId, sourceId);
		if (source.getStatus() == SourceDocumentStatus.EXTRACTING || source.getStatus() == SourceDocumentStatus.INDEXING) {
			throw new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_PROCESSING);
		}
		ragIngestService.deleteIndex(source);
		maskingCandidateRepository.deleteAllBySourceDocumentId(sourceId);
		Long oid = largeObjectCleaner.extractedTextOid(sourceId);
		repository.delete(source);
		repository.flush();
		largeObjectCleaner.unlink(oid);
	}

	@Transactional(readOnly = true)
	public List<SourceDocument> list(UUID handoverId) {
		return repository.findAllByHandoverId(handoverId);
	}

	private SourceDocument findExternal(UUID handoverId, UUID sourceId) {
		SourceDocument source = repository.findById(sourceId)
				.orElseThrow(() -> new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND));
		if (!source.getHandoverId().equals(handoverId) || source.getSourceType() == SourceType.FILE) {
			throw new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND);
		}
		return source;
	}

	private void validateSlackUrl(String raw) {
		try {
			URI uri = URI.create(raw == null ? "" : raw.trim());
			String host = uri.getHost();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
					|| !(host.equals("slack.com") || host.endsWith(".slack.com"))) {
				throw new IllegalArgumentException();
			}
		} catch (Exception e) {
			throw new BusinessException(ErrorCode.AI_EXTERNAL_SOURCE_INVALID_URL, "올바른 Slack 메시지 링크를 입력해주세요.");
		}
	}

	private boolean enabled(Boolean value) { return value == null || value; }
	private boolean isBlank(String value) { return value == null || value.isBlank(); }
	private String firstNonBlank(String... values) {
		for (String value : values) if (!isBlank(value)) return value.trim();
		return "제목 없음";
	}
	private String joinText(String title, String description) {
		return (title + "\n\n" + (description == null ? "" : description)).strip();
	}
	private String hostTitle(String url) { return URI.create(url).getHost(); }

	public record Result(SourceDocument source, boolean fetched) {}
}
