package com.baton.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.baton.ai.dto.QuestionOption;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 업로드 자료만으로는 AI가 확신할 수 없어 인계자에게 직접 확인받아야 하는 질문 한 건.
 * 답변(또는 모름·해당 없음)은 targetSections에 해당하는 문서 섹션에만 반영된다.
 */
@Entity
@Table(name = "clarification_questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClarificationQuestion {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "question_text", nullable = false)
	private String questionText;

	@Column(name = "reason")
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'INTERVIEW'")
	private ClarificationQuestionType type;

	@Lob
	@Column(name = "evidence")
	private String evidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<QuestionOption> options;

	/** 답변이 반영될 문서 섹션. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "target_sections")
	private List<DraftSection> targetSections;

	/** 중요도 순위(1이 가장 중요). 목록은 이 순서로 보여준다. */
	@Column(name = "priority")
	private Integer priority;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClarificationQuestionStatus status;

	@Lob
	@Column(name = "answer")
	private String answer;

	/** 현재 상태가 문서에 반영된 시각. 상태나 답변이 바뀌면 null로 돌아가 다시 반영 대상이 된다. */
	@Column(name = "applied_at")
	private Instant appliedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private ClarificationQuestion(UUID handoverId, ClarificationQuestionType type, String questionText,
			String reason, String evidence, List<QuestionOption> options, List<DraftSection> targetSections,
			Integer priority) {
		this.handoverId = handoverId;
		this.type = type == null ? ClarificationQuestionType.INTERVIEW : type;
		this.questionText = questionText;
		this.reason = reason;
		this.evidence = evidence;
		this.options = options == null ? List.of() : options;
		this.targetSections = targetSections == null ? List.of() : targetSections;
		this.priority = priority;
		this.status = ClarificationQuestionStatus.PENDING;
	}

	public static ClarificationQuestion create(UUID handoverId, ClarificationQuestionType type, String questionText,
			String reason, String evidence, List<QuestionOption> options, List<DraftSection> targetSections,
			Integer priority) {
		return new ClarificationQuestion(handoverId, type, questionText, reason, evidence, options, targetSections,
				priority);
	}

	public void answer(String answer) {
		if (answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("답변은 비어 있을 수 없습니다.");
		}
		if (this.status == ClarificationQuestionStatus.ANSWERED && answer.equals(this.answer)) {
			return;
		}
		this.answer = answer;
		this.status = ClarificationQuestionStatus.ANSWERED;
		this.appliedAt = null;
	}

	/** 답변 없는 상태(모름·해당 없음·나중에 답하기)로 바꾼다. */
	public void resolveWithoutAnswer(ClarificationQuestionStatus status) {
		if (status == ClarificationQuestionStatus.PENDING || status == ClarificationQuestionStatus.ANSWERED) {
			throw new IllegalArgumentException("답변 없이 바꿀 수 없는 상태입니다: " + status);
		}
		if (this.status == status) {
			return;
		}
		this.answer = null;
		this.status = status;
		this.appliedAt = null;
	}

	/** 문서에 반영해야 하는데 아직 반영되지 않았는가. */
	public boolean needsApply() {
		return status.isResolved() && appliedAt == null;
	}

	public void markApplied(Instant appliedAt) {
		this.appliedAt = appliedAt;
	}

	public List<DraftSection> getTargetSections() {
		return targetSections == null ? List.of() : targetSections;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
