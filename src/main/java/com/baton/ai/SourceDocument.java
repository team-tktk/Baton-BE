package com.baton.ai;

import java.time.Instant;
import java.util.UUID;

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
 * 인수인계(handover)에 업로드되어 RAG 인덱싱 대상이 된 원본 파일 한 건.
 * 실제 파일 바이너리는 저장하지 않고(별도 스토리지 담당 영역), 인덱싱 상태와
 * 벡터 검색 결과를 사람이 읽을 근거(citation)로 되짚어주기 위한 메타데이터만 가진다.
 */
@Entity
@Table(name = "source_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SourceDocument {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "handover_id", nullable = false)
	private UUID handoverId;

	@Column(name = "file_name", nullable = false)
	private String fileName;

	@Column(name = "mime_type")
	private String mimeType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SourceDocumentStatus status;

	/** Tika로 추출한 원문 텍스트 전체. 벡터 검색용 청크와 별개로, 초안 생성 시 문서 전체 맥락이 필요해 보관한다. */
	@Lob
	@Column(name = "extracted_text")
	private String extractedText;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private SourceDocument(UUID handoverId, String fileName, String mimeType) {
		this.handoverId = handoverId;
		this.fileName = fileName;
		this.mimeType = mimeType;
		this.status = SourceDocumentStatus.EXTRACTING;
	}

	public static SourceDocument create(UUID handoverId, String fileName, String mimeType) {
		return new SourceDocument(handoverId, fileName, mimeType);
	}

	public void markIndexed(String extractedText) {
		this.status = SourceDocumentStatus.INDEXED;
		this.extractedText = extractedText;
		this.updatedAt = Instant.now();
	}

	public void markFailed() {
		this.status = SourceDocumentStatus.FAILED;
		this.updatedAt = Instant.now();
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}
}
