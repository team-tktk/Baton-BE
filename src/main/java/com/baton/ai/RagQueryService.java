package com.baton.ai;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
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

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 100;
	private static final String NOT_FOUND_MARKER = "NOT_FOUND";
	private static final String CONFIRM_REQUIRED_MARKER = "CONFIRM_REQUIRED";

	private final HybridRagRetriever retriever;
	private final ChatClient chatClient;
	private final ChatMessageRepository chatMessageRepository;

	@Transactional
	public ChatAnswerResponse answer(UUID handoverId, UUID askedBy, String question) {
		List<RagEvidence> matches = retriever.retrieve(handoverId, question);

		if (matches.isEmpty()) {
			return fallbackToGeneralKnowledge(handoverId, askedBy, question);
		}

		String context = matches.stream()
				.map(RagEvidence::context)
				.collect(Collectors.joining("\n---\n"));

		GeneratedAnswer generated = parseGeneratedAnswer(generateAnswer(context, question));

		if (generated.answer() == null || generated.answer().isBlank() || generated.answer().contains(NOT_FOUND_MARKER)) {
			return fallbackToGeneralKnowledge(handoverId, askedBy, question);
		}

		ChatMessage saved = chatMessageRepository.save(ChatMessage.create(
				handoverId, askedBy, question, generated.answer().trim(), true,
				generated.requiresConfirmation(), buildCitations(matches)));
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

	/**
	 * 문서 근거로 답을 못 찾았을 때 바로 "모른다"로 끝내지 않고, 일반 지식으로 답할 수 있는지,
	 * 되물어야 할 만큼 애매한 질문인지, 아니면 팀장님/인계자에게 직접 물어보라고 안내해야 하는지
	 * AI가 판단해서 답하게 한다. persistNotFound는 이 판단 호출 자체가 실패했을 때만 쓰는 최후 수단이다.
	 */
	private ChatAnswerResponse fallbackToGeneralKnowledge(UUID handoverId, UUID askedBy, String question) {
		String answer;
		try {
			answer = generateGeneralKnowledgeAnswer(question);
		} catch (RuntimeException e) {
			log.warn("General-knowledge fallback failed for handoverId={}", handoverId, e);
			return persistNotFound(handoverId, askedBy, question);
		}

		if (answer == null || answer.isBlank() || answer.contains(NOT_FOUND_MARKER)) {
			return persistNotFound(handoverId, askedBy, question);
		}

		ChatMessage saved = chatMessageRepository.save(ChatMessage.create(
				handoverId, askedBy, question, answer.trim(), false, List.of()));
		return ChatAnswerResponse.from(saved);
	}

	private String generateGeneralKnowledgeAnswer(String question) {
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(RagPrompts.GENERAL_KNOWLEDGE_SYSTEM_TEMPLATE);
		Message systemMessage = systemPromptTemplate.createMessage(Map.of());
		Message userMessage = new UserMessage(question);

		return chatClient.prompt()
				.messages(List.of(systemMessage, userMessage))
				.call()
				.content();
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

	/** Preserve different excerpts from the same PDF for previous/next navigation. */
	private List<Citation> buildCitations(List<RagEvidence> matches) {
		return matches.stream().map(RagEvidence::citation).distinct().toList();
	}

	private GeneratedAnswer parseGeneratedAnswer(String raw) {
		if (raw == null) return new GeneratedAnswer(null, false);
		boolean requiresConfirmation = raw.contains(CONFIRM_REQUIRED_MARKER);
		String answer = raw.replace(CONFIRM_REQUIRED_MARKER, "").trim();
		return new GeneratedAnswer(answer, requiresConfirmation);
	}

	private record GeneratedAnswer(String answer, boolean requiresConfirmation) { }
}
