package com.baton.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.baton.ai.dto.Citation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인수인계별 AI Q&A 대화 한 턴(질문+답변)의 기록. */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "asked_by", nullable = false)
	private UUID askedBy;

	@Lob
	@Column(nullable = false)
	private String question;

	@Lob
	@Column
	private String answer;

	@Column(nullable = false)
	private boolean grounded;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private List<Citation> citations;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	private ChatMessage(UUID handoverId, UUID askedBy, String question, String answer, boolean grounded, List<Citation> citations) {
		this.handoverId = handoverId;
		this.askedBy = askedBy;
		this.question = question;
		this.answer = answer;
		this.grounded = grounded;
		this.citations = citations;
	}

	public static ChatMessage create(UUID handoverId, UUID askedBy, String question, String answer, boolean grounded, List<Citation> citations) {
		return new ChatMessage(handoverId, askedBy, question, answer, grounded, citations);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}
}
