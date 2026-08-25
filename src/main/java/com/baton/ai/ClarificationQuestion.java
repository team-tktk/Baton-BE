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
 * 답변이 확정되면 초안 재생성 시 근거로 사용된다.
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ClarificationQuestionStatus status;

	@Lob
	@Column(name = "answer")
	private String answer;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private ClarificationQuestion(UUID handoverId, ClarificationQuestionType type, String questionText,
			String reason, String evidence, List<QuestionOption> options) {
		this.handoverId = handoverId;
		this.type = type == null ? ClarificationQuestionType.INTERVIEW : type;
		this.questionText = questionText;
		this.reason = reason;
		this.evidence = evidence;
		this.options = options;
		this.status = ClarificationQuestionStatus.PENDING;
	}

	public static ClarificationQuestion create(UUID handoverId, ClarificationQuestionType type, String questionText,
			String reason, String evidence, List<QuestionOption> options) {
		return new ClarificationQuestion(handoverId, type, questionText, reason, evidence, options);
	}

	public void answer(String answer) {
		if (answer == null || answer.isBlank()) {
			throw new IllegalArgumentException("답변은 비어 있을 수 없습니다.");
		}
		this.answer = answer;
		this.status = ClarificationQuestionStatus.ANSWERED;
	}

	public void skip() {
		this.answer = null;
		this.status = ClarificationQuestionStatus.SKIPPED;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
