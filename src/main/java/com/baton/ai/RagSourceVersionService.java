package com.baton.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.dto.RagSourceVersionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RagSourceVersionService {
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final RagSourceVersionRepository repository;
	private final ObjectMapper objectMapper;

	/** 마스킹 검수 전 원문은 보관하지 않는다. 현재 검색에 쓰인 안전한 본문만 이력으로 남긴다. */
	@Transactional
	public void archive(SourceDocument source) {
		if (source == null || source.getId() == null || source.getStatus() != SourceDocumentStatus.INDEXED
				|| source.getExtractedText() == null || source.getExtractedText().isBlank()) return;
		String locator = source.getOriginalUrl() == null || source.getOriginalUrl().isBlank()
				? source.getConversationName() : source.getOriginalUrl();
		archive(source.getHandoverId(), source.getId(), source.getSourceType().name(), source.getFileName(),
				locator, source.getExtractedText(), null, source.getUpdatedAt());
	}

	@Transactional
	public void archive(HandoverDraft draft) {
		if (draft == null || draft.getId() == null || draft.getContent() == null) return;
		archive(draft.getHandoverId(), draft.getId(), "HANDOVER_DRAFT", "인수인계 초안",
				"revision " + draft.getRevision(), json(draft.getContent()), draft.getRevision(), draft.getUpdatedAt());
	}

	@Transactional
	public void archive(ClarificationQuestion question) {
		if (question == null || question.getId() == null || question.getStatus() != ClarificationQuestionStatus.ANSWERED
				|| question.getAnswer() == null || question.getAnswer().isBlank()) return;
		archive(question.getHandoverId(), question.getId(), "CLARIFICATION_ANSWER", "확인 질문 답변",
				question.getQuestionText(), "질문: " + question.getQuestionText() + "\n답변: " + question.getAnswer(),
				null, question.getCreatedAt());
	}

	@Transactional(readOnly = true)
	public List<RagSourceVersionResponse> list(UUID handoverId, UUID sourceId, int page, int size) {
		var pageable = PageRequest.of(Math.max(page, 0), clampSize(size));
		List<RagSourceVersion> versions = sourceId == null
				? repository.findAllByHandoverIdOrderByArchivedAtDesc(handoverId, pageable)
				: repository.findAllByHandoverIdAndSourceIdOrderByArchivedAtDesc(handoverId, sourceId, pageable);
		return versions.stream().map(RagSourceVersionResponse::from).toList();
	}

	private int clampSize(int size) {
		return size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
	}

	private void archive(UUID handoverId, UUID sourceId, String type, String title, String locator, String content,
			Long revision, java.time.Instant updatedAt) {
		String hash = sha256(String.join("\u001f",
				type,
				title == null ? "" : title,
				locator == null ? "" : locator,
				revision == null ? "" : revision.toString(),
				content));
		if (!repository.existsBySourceIdAndContentHash(sourceId, hash)) {
			repository.save(RagSourceVersion.create(handoverId, sourceId, type, title,
					locator, content, hash, revision, updatedAt));
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("RAG 자료 버전을 직렬화하지 못했습니다.", e);
		}
	}

	private String sha256(String content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(content.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
