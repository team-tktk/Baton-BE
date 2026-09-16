package com.baton.readiness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * 부족 항목 하나에 대한 보완안. 문서 섹션 하나만 대상으로 하며, 사용자가 적용하기 전에는 문서를 바꾸지 않는다.
 * before/after는 대상 섹션만 채운 HandoverDraftContent 스냅샷이다(나머지 섹션은 null).
 * baseRevision은 보완안을 만들 때 본 문서 버전으로, 적용 시점의 문서 버전과 다르면 적용하지 않는다.
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReadinessArea area;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private DraftSection section;

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

	/** 수정안. 추가 질문 답변 대기 중이면 null. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "after_content")
	private HandoverDraftContent after;

	@Column(name = "change_summary", columnDefinition = "TEXT")
	private String changeSummary;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<FixQuestion> questions;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<ReadinessEvidence> evidence;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private ReadinessFix(UUID handoverId, UUID evaluationId, ReadinessArea area, DraftSection section,
			long baseRevision, HandoverDraftContent currentContent) {
		this.handoverId = handoverId;
		this.evaluationId = evaluationId;
		this.area = area;
		this.section = section;
		this.baseRevision = baseRevision;
		this.before = section.only(currentContent);
		this.status = ReadinessFixStatus.NEEDS_INPUT;
		this.questions = new ArrayList<>();
		this.evidence = List.of();
	}

	public static ReadinessFix open(UUID handoverId, UUID evaluationId, ReadinessArea area, DraftSection section,
			long baseRevision, HandoverDraftContent currentContent) {
		return new ReadinessFix(handoverId, evaluationId, area, section, baseRevision, currentContent);
	}

	/** 자료(또는 답변)에서 찾은 사실로 만든 수정안을 붙인다. 대상 섹션 밖의 값은 버린다. */
	public void propose(HandoverDraftContent patch, String changeSummary, List<ReadinessEvidence> evidence) {
		requireOpen();
		this.after = section.only(patch);
		this.changeSummary = changeSummary;
		this.evidence = List.copyOf(evidence);
		this.status = ReadinessFixStatus.PROPOSED;
	}

	/** 자료에 답이 없어 추가 질문을 붙인다. 이미 답한 질문은 유지하고, 새 질문은 이어지는 id로 추가한다. */
	public void askMore(List<FixQuestion> newQuestions) {
		requireOpen();
		List<FixQuestion> merged = new ArrayList<>(questions);
		int next = questions.size() + 1;
		for (FixQuestion question : newQuestions) {
			merged.add(new FixQuestion("q" + next++, question.question(), question.reason(), null));
		}
		this.questions = merged;
		this.after = null;
		this.changeSummary = null;
		this.status = ReadinessFixStatus.NEEDS_INPUT;
	}

	/** 추가 질문에 답한다. 모르는 질문 id는 400. */
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
			throw new BusinessException(ErrorCode.BAD_REQUEST, "존재하지 않는 추가 질문입니다: " + questionId);
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
