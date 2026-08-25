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
import org.springframework.stereotype.Service;

import com.baton.ai.dto.ChatAnswerResponse;
import com.baton.ai.dto.Citation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 질문을 받아 해당 인수인계(handover)에 속한 문서 청크만 검색해 근거 기반으로 답변한다.
 * 근거가 없으면 임의로 답하지 않고 grounded=false로 응답한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagQueryService {

	private static final int TOP_K = 5;
	private static final String NOT_FOUND_MARKER = "NOT_FOUND";

	private final VectorStore vectorStore;
	private final ChatClient chatClient;
	private final SourceDocumentRepository sourceDocumentRepository;

	public ChatAnswerResponse answer(UUID handoverId, String question) {
		List<Document> matches = search(handoverId, question);

		if (matches.isEmpty()) {
			return ChatAnswerResponse.notFound();
		}

		String context = matches.stream()
				.map(Document::getText)
				.collect(Collectors.joining("\n---\n"));

		String answer = generateAnswer(context, question);

		if (answer == null || answer.isBlank() || answer.contains(NOT_FOUND_MARKER)) {
			return ChatAnswerResponse.notFound();
		}

		return ChatAnswerResponse.of(answer.trim(), buildCitations(matches));
	}

	private List<Document> search(UUID handoverId, String question) {
		var filterExpression = new FilterExpressionBuilder()
				.eq("handoverId", handoverId.toString())
				.build();

		SearchRequest searchRequest = SearchRequest.builder()
				.query(question)
				.topK(TOP_K)
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

	/** 같은 문서에서 나온 청크는 하나의 근거로 묶는다. */
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
							.map(this::toCitation)
							.orElse(null));
		}

		citationsBySourceId.values().removeIf(Objects::isNull);
		return List.copyOf(citationsBySourceId.values());
	}

	private Citation toCitation(SourceDocument sourceDocument) {
		return new Citation(
				sourceDocument.getId(),
				sourceDocument.getFileName(),
				null,
				sourceDocument.getUpdatedAt() != null ? sourceDocument.getUpdatedAt() : Instant.now());
	}
}
