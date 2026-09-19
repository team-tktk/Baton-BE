package com.baton.readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.baton.ai.DraftSection;
import com.baton.ai.dto.HandoverDraftContent;
import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 부족 영역 여러 개를 한 번에 보완하는 보완안. 사용자가 적용하기 전에는 문서를 바꾸지 않는다.
 * 흐름: 시작(평가의 질문·미룬 확인 질문을 모음, AI 호출 없음) → 답변 저장 → 보완안 만들기(AI 1회) → 적용.
 * before/after는 고칠 섹션(sections)만 채운 HandoverDraftContent 스냅샷이다(나머지 섹션은 null).
 * baseRevision은 보완을 시작할 때 본 문서 버전으로, 적용 시점의 문서 버전과 다르면 적용하지 않는다.
 */
@Entity
@Table(name = "readiness_fixes",
		indexes = @Index(name = "idx_readiness_fixes_handover", columnList = "handover_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReadinessFix {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "evaluation_id", nullable = false)
	private UUID evaluationId;

	/** 보완할 영역별 상태·고칠 섹션·결과. 요청한 영역 순서. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "area_results", nullable = false)
	private List<FixAreaResult> areaResults;

	/** 고칠 수 있는 섹션(영역별 섹션의 합집합). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<DraftSection> sections;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReadinessFixStatus status;

	@Column(name = "base_revision", nullable = false)
	private long baseRevision;

	/** 적용 후 문서 버전. 적용 전이면 null. */
	@Column(name = "applied_revision")
	private Long appliedRevision;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "before_content", nullable = false)
	private HandoverDraftContent before;

	/** 수정안(수정안이 있는 영역의 섹션만 바뀌고, 나머지 섹션은 before와 같다). 수정안이 없으면 null. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_content")
	private HandoverDraftContent after;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<FixQuestion> questions;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private ReadinessFix(UUID handoverId, UUID evaluationId, List<ReadinessItem> items, List<FixQuestion> questions,
			long baseRevision, HandoverDraftContent currentContent) {
		this.handoverId = handoverId;
		this.evaluationId = evaluationId;
		this.areaResults = items.stream().map(FixAreaResult::open).toList();
		this.sections = areaResults.stream()
				.flatMap(result -> result.sections().stream())
				.distinct()
				.toList();
		this.baseRevision = baseRevision;
		this.before = DraftSection.extract(currentContent, sections);
		this.status = ReadinessFixStatus.NEEDS_INPUT;
		this.questions = new ArrayList<>();
		addQuestions(questions);
	}

	/** 보완을 시작한다. items는 보완할 영역의 평가 결과, questions는 처음에 물을 질문(id는 여기서 붙인다). */
	public static ReadinessFix open(UUID handoverId, UUID evaluationId, List<ReadinessItem> items,
			List<FixQuestion> questions, long baseRevision, HandoverDraftContent currentContent) {
		return new ReadinessFix(handoverId, evaluationId, items, questions, baseRevision, currentContent);
	}

	/**
	 * 보완안 만들기 결과를 반영한다. 수정안이 있는 영역이 하나라도 있으면 PROPOSED, 없으면 NEEDS_INPUT.
	 * newQuestions는 아직 부족한 영역에 새로 물을 질문(이미 답한 질문은 유지된다).
	 */
	public void recordGeneration(List<FixAreaResult> results, HandoverDraftContent after, List<FixQuestion> newQuestions) {
		requireOpen();
		this.areaResults = List.copyOf(results);
		addQuestions(newQuestions);
		boolean anyProposed = results.stream().anyMatch(FixAreaResult::proposed);
		this.after = anyProposed ? DraftSection.extract(after, sections) : null;
		this.status = anyProposed ? ReadinessFixStatus.PROPOSED : ReadinessFixStatus.NEEDS_INPUT;
	}

	/** 수정안이 있는 영역이 고치는 섹션. 적용할 때 이 섹션만 문서에 반영한다. */
	public List<DraftSection> proposedSections() {
		return areaResults.stream()
				.filter(FixAreaResult::proposed)
				.flatMap(result -> result.sections().stream())
				.distinct()
				.toList();
	}

	public Optional<FixAreaResult> areaResult(ReadinessArea area) {
		return areaResults.stream().filter(result -> result.area() == area).findFirst();
	}

	private void addQuestions(List<FixQuestion> newQuestions) {
		List<FixQuestion> merged = new ArrayList<>(questions);
		int next = questions.size() + 1;
		for (FixQuestion question : newQuestions) {
			merged.add(question.withId("q" + next++));
		}
		this.questions = merged;
	}

	/** 질문에 답한다(답만 저장하고 AI는 부르지 않는다). 모르는 질문 id는 400. */
	public void answer(String questionId, String answer) {
		requireOpen();
		List<FixQuestion> updated = new ArrayList<>(questions.size());
		boolean found = false;
		for (FixQuestion question : questions) {
			if (question.id().equals(questionId)) {
				updated.add(question.withAnswer(answer));
				found = true;
			} else {
				updated.add(question);
			}
		}
		if (!found) {
			throw new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 보완 질문입니다: " + questionId);
		}
		this.questions = updated;
	}

	public List<FixQuestion> answeredQuestions() {
		return questions.stream().filter(FixQuestion::hasAnswer).toList();
	}

	/** 문서가 보완안을 만든 뒤 바뀌었으면 true — 이 보완안은 더 이상 적용할 수 없다. */
	public boolean isStaleAgainst(long currentRevision) {
		return baseRevision != currentRevision;
	}

	public void markApplied(long appliedRevision) {
		if (status != ReadinessFixStatus.PROPOSED) {
			throw new BusinessException(ErrorCode.READINESS_FIX_INVALID_STATE, "확인할 수정안이 있는 보완안만 적용할 수 있습니다.");
		}
		this.appliedRevision = appliedRevision;
		this.status = ReadinessFixStatus.APPLIED;
	}

	public void discard() {
		requireOpen();
		this.status = ReadinessFixStatus.DISCARDED;
	}

	private void requireOpen() {
		if (!status.isOpen()) {
			throw new BusinessException(ErrorCode.READINESS_FIX_INVALID_STATE, "이미 적용했거나 취소한 보완안입니다.");
		}
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}
}
