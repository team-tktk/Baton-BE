package com.baton.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.baton.ai.dto.Citation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** Combines semantic and lexical retrieval, then applies BATON's source precedence. */
@Component
@RequiredArgsConstructor
class HybridRagRetriever {
	private static final int CANDIDATE_K = 15;
	private static final int RESULT_K = 6;
	private static final double SIMILARITY_THRESHOLD = 0.3;
	private static final double KEYWORD_THRESHOLD = 0.08;
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

	private final VectorStore vectorStore;
	private final JdbcTemplate jdbcTemplate;
	private final SourceDocumentRepository sourceDocumentRepository;
	private final HandoverDraftRepository handoverDraftRepository;
	private final ClarificationQuestionRepository clarificationQuestionRepository;
	private final ObjectMapper objectMapper;

	List<RagEvidence> retrieve(UUID handoverId, String question) {
		Map<UUID, SourceDocument> currentSources = currentSources(handoverId);
		List<Document> vectors = vectorSearch(handoverId, question).stream()
				.filter(document -> isCurrent(document, currentSources)).toList();
		List<Document> lexicalPool = new ArrayList<>(storedChunks(handoverId));
		lexicalPool.addAll(liveEvidence(handoverId));
		List<Document> keywords = lexicalPool.stream()
				.filter(document -> isLive(document) || isCurrent(document, currentSources))
				.map(document -> document.mutate().score(KeywordScorer.score(question, searchableText(document))).build())
				.filter(document -> document.getScore() != null && document.getScore() >= KEYWORD_THRESHOLD)
				.sorted(Comparator.comparing(Document::getScore).reversed()).limit(CANDIDATE_K).toList();

		Map<String, Ranked> merged = new LinkedHashMap<>();
		addRanks(merged, vectors);
		addRanks(merged, keywords);
		return merged.values().stream()
				.map(ranked -> toEvidence(ranked.document(), currentSources))
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator.comparingDouble((RagEvidence evidence) -> merged.get(evidence.document().getId()).score()
						+ evidence.sourcePriority() * 0.002).reversed()
						.thenComparing(RagEvidence::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.limit(RESULT_K).toList();
	}

	private List<Document> vectorSearch(UUID handoverId, String question) {
		var filter = new FilterExpressionBuilder().eq("handoverId", handoverId.toString()).build();
		return vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(CANDIDATE_K)
				.similarityThreshold(SIMILARITY_THRESHOLD).filterExpression(filter).build());
	}

	private List<Document> storedChunks(UUID handoverId) {
		return jdbcTemplate.query("""
				SELECT id::text, content, metadata::text
				FROM vector_store
				WHERE metadata->>'handoverId' = ?
				""", (rs, rowNum) -> storedDocument(rs), handoverId.toString());
	}

	private Document storedDocument(ResultSet rs) throws SQLException {
		try {
			Map<String, Object> metadata = objectMapper.readValue(rs.getString("metadata"), MAP_TYPE);
			return Document.builder().id(rs.getString("id")).text(rs.getString("content")).metadata(metadata).build();
		} catch (Exception e) {
			throw new SQLException("벡터 청크 메타데이터를 읽지 못했습니다.", e);
		}
	}

	private Map<UUID, SourceDocument> currentSources(UUID handoverId) {
		Map<UUID, SourceDocument> result = new HashMap<>();
		for (SourceDocument source : sourceDocumentRepository.findAllByHandoverId(handoverId)) {
			if (source.isEnabled() && source.getStatus() == SourceDocumentStatus.INDEXED) result.put(source.getId(), source);
		}
		return result;
	}

	private boolean isCurrent(Document document, Map<UUID, SourceDocument> sources) {
		try {
			UUID sourceId = UUID.fromString(String.valueOf(document.getMetadata().get("sourceDocumentId")));
			SourceDocument source = sources.get(sourceId);
			return source != null && source.getChunkIds() != null && source.getChunkIds().contains(document.getId());
		} catch (RuntimeException e) {
			return false;
		}
	}

	private List<Document> liveEvidence(UUID handoverId) {
		List<Document> result = new ArrayList<>();
		clarificationQuestionRepository.findAllByHandoverIdAndStatus(handoverId, ClarificationQuestionStatus.ANSWERED)
				.forEach(question -> result.add(liveDocument("CLARIFICATION_ANSWER", question.getId(), "확인 질문 답변",
						question.getQuestionText(), "질문: " + question.getQuestionText() + "\n답변: " + question.getAnswer(),
						question.getCreatedAt(), 4)));
		handoverDraftRepository.findByHandoverId(handoverId).ifPresent(draft -> {
			Map<String, Object> sections = objectMapper.convertValue(draft.getContent(), MAP_TYPE);
			sections.forEach((section, value) -> {
				if (value != null && !isEmpty(value)) {
					result.add(liveDocument("HANDOVER_DRAFT", draft.getId(), "최신 인수인계 초안", section,
							section + ": " + value, draft.getUpdatedAt(), 3));
				}
			});
		});
		return result;
	}

	private Document liveDocument(String type, UUID id, String title, String locator, String text, Instant updatedAt, int priority) {
		return Document.builder().id(type + ":" + id + ":" + locator).text(text)
				.metadata(Map.of("liveType", type, "sourceId", id.toString(), "title", title, "locator", locator,
						"updatedAt", updatedAt.toString(), "sourcePriority", priority)).build();
	}

	private boolean isEmpty(Object value) {
		return value instanceof String text && text.isBlank() || value instanceof List<?> list && list.isEmpty();
	}

	private boolean isLive(Document document) {
		return document.getMetadata().containsKey("liveType");
	}

	private String searchableText(Document document) {
		return document.getText() + " " + document.getMetadata().getOrDefault("fileName", "")
				+ " " + document.getMetadata().getOrDefault("title", "")
				+ " " + document.getMetadata().getOrDefault("sectionHeading", "");
	}

	private void addRanks(Map<String, Ranked> merged, List<Document> documents) {
		for (int index = 0; index < documents.size(); index++) {
			Document document = documents.get(index);
			Ranked ranked = merged.computeIfAbsent(document.getId(), ignored -> new Ranked(document, 0));
			double rankScore = 1.0 / (60 + index + 1);
			merged.put(document.getId(), new Ranked(ranked.document(), ranked.score() + rankScore));
		}
	}

	private RagEvidence toEvidence(Document document, Map<UUID, SourceDocument> sources) {
		if (isLive(document)) {
			Map<String, Object> metadata = document.getMetadata();
			UUID id = UUID.fromString(metadata.get("sourceId").toString());
			Instant updatedAt = Instant.parse(metadata.get("updatedAt").toString());
			Citation citation = new Citation(id, metadata.get("title").toString(), metadata.get("locator").toString(),
					null, updatedAt, metadata.get("liveType").toString(), null, null, excerpt(document.getText()), List.of());
			return new RagEvidence(document, citation, ((Number) metadata.get("sourcePriority")).intValue(), updatedAt);
		}
		try {
			SourceDocument source = sources.get(UUID.fromString(document.getMetadata().get("sourceDocumentId").toString()));
			if (source == null) return null;
			int priority = source.getSourceType() == SourceType.FILE ? 2 : 1;
			return new RagEvidence(document, EvidenceCitations.fromMatch(source, document), priority, source.getUpdatedAt());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private String excerpt(String text) {
		return text.length() <= 500 ? text : text.substring(0, 497) + "...";
	}

	private record Ranked(Document document, double score) { }
}
