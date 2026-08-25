package com.baton.ai;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.ChatMessagePageResponse;
import com.baton.ai.dto.ChatMessageResponse;
import com.baton.ai.dto.Citation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문을 받아 해당 인수인계(handover)에 속한 문서 청크만 검색해 근거 기반으로 답변한다.
 * 근거가 없거나 유사도가 낮으면 임의로 답하지 않고 grounded=false로 응답한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagQueryService {

	private static final int TOP_K = 5;
	private static final double SIMILARITY_THRESHOLD = 0.3;
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 100;
	private static final String NOT_FOUND_MARKER = "NOT_FOUND";

	private final VectorStore vectorStore;
	private final ChatClient chatClient;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final ChatMessageRepository chatMessageRepository;

	@Transactional
	public ChatAnswerResponse answer(UUID handoverId, UUID askedBy, String question) {
		List<Document> matches = search(handoverId, question);

		if (matches.isEmpty()) {
			return persistNotFound(handoverId, askedBy, question);
		}

		String context = matches.stream()
				.map(Document::getText)
				.collect(Collectors.joining("\n---\n"));

		String answer = generateAnswer(context, question);

		if (answer == null || answer.isBlank() || answer.contains(NOT_FOUND_MARKER)) {
			return persistNotFound(handoverId, askedBy, question);
		}

		ChatMessage saved = chatMessageRepository.save(ChatMessage.create(
				handoverId, askedBy, question, answer.trim(), true, buildCitations(matches)));
		return ChatAnswerResponse.from(saved);
	}

	@Transactional(readOnly = true)
	public ChatMessagePageResponse listMessages(UUID handoverId, Instant cursor, int size) {
		int pageSize = clampSize(size);
		Pageable pageable = PageRequest.of(0, pageSize + 1);
		List<ChatMessage> rows = cursor == null
				? chatMessageRepository.findByHandoverIdOrderByCreatedAtAsc(handoverId, pageable)
				: chatMessageRepository.findByHandoverIdAndCreatedAtAfterOrderByCreatedAtAsc(handoverId, cursor, pageable);

		boolean hasNext = rows.size() > pageSize;
		List<ChatMessage> page = hasNext ? rows.subList(0, pageSize) : rows;
		String nextCursor = hasNext ? page.get(page.size() - 1).getCreatedAt().toString() : null;

		List<ChatMessageResponse> items = page.stream().map(ChatMessageResponse::from).toList();
		return new ChatMessagePageResponse(items, nextCursor, hasNext);
	}

	private int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.min(size, MAX_PAGE_SIZE);
	}

	private ChatAnswerResponse persistNotFound(UUID handoverId, UUID askedBy, String question) {
		ChatMessage saved = chatMessageRepository.save(ChatMessage.create(
				handoverId, askedBy, question, null, false, List.of()));
		return ChatAnswerResponse.from(saved);
	}

	private List<Document> search(UUID handoverId, String question) {
		var filterExpression = new FilterExpressionBuilder()
				.eq("handoverId", handoverId.toString())
				.build();

		SearchRequest searchRequest = SearchRequest.builder()
				.query(question)
				.topK(TOP_K)
				.similarityThreshold(SIMILARITY_THRESHOLD)
				.filterExpression(filterExpression)
				.build();

		return vectorStore.similaritySearch(searchRequest);
	}

	private String generateAnswer(String context, String question) {
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(RagPrompts.SYSTEM_TEMPLATE);
		Message systemMessage = systemPromptTemplate.createMessage(Map.of("context", context));
		Message userMessage = new UserMessage(question);

		return chatClient.prompt()
				.messages(List.of(systemMessage, userMessage))
				.call()
				.content();
	}

	/** 같은 문서에서 나온 청크는 하나의 근거로 묶는다(가장 먼저 매칭된 청크의 위치를 locator로 남긴다). */
	private List<Citation> buildCitations(List<Document> matches) {
		Map<UUID, Citation> citationsBySourceId = new LinkedHashMap<>();

		for (Document match : matches) {
			Object rawId = match.getMetadata().get("sourceDocumentId");
			if (rawId == null) {
				continue;
			}
			UUID sourceDocumentId = UUID.fromString(rawId.toString());

			citationsBySourceId.computeIfAbsent(sourceDocumentId, id ->
					sourceDocumentRepository.findById(id)
							.map(sourceDocument -> toCitation(sourceDocument, match))
							.orElse(null));
		}

		citationsBySourceId.values().removeIf(Objects::isNull);
		return List.copyOf(citationsBySourceId.values());
	}

	private Citation toCitation(SourceDocument sourceDocument, Document match) {
		return new Citation(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				buildLocator(match),
				sourceDocument.getId(),   // fileId == sourceId (같은 SourceDocument id) — 다운로드 API용
				sourceDocument.getUpdatedAt() != null ? sourceDocument.getUpdatedAt() : Instant.now());
	}

	/** 벡터 청크 메타데이터의 chunkIndex/total_chunks로 문서 내 대략적인 위치를 표시한다. */
	private String buildLocator(Document match) {
		Object chunkIndex = match.getMetadata().get("chunkIndex");
		Object totalChunks = match.getMetadata().get("total_chunks");
		if (chunkIndex == null || totalChunks == null) {
			return null;
		}
		int index = Integer.parseInt(chunkIndex.toString());
		return "청크 %d/%s".formatted(index + 1, totalChunks);
	}
}
