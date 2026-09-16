package com.baton.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 *
 * @DynamicUpdate: 바뀐 컬럼만 UPDATE 한다. 없으면 상태만 바꿔도 extracted_text(@Lob)를 매번 다시 써서
 * PostgreSQL Large Object 복사본이 계속 쌓인다(로컬에서 확인함). 텍스트를 바꾸는 경우의 이전 객체 정리는
 * LargeObjectCleaner가 맡는다.
 */
@Entity
@DynamicUpdate
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

	@Column(name = "file_size", nullable = false)
	private long fileSize;

	/** S3에 저장된 원본 파일의 오브젝트 키. 다운로드 시 이 키로 S3에서 꺼내온다. */
	@Column(name = "s3_key", nullable = false)
	private String s3Key;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SourceDocumentStatus status;

	/** Tika로 추출한 원문 텍스트 전체. 벡터 검색용 청크와 별개로, 초안 생성 시 문서 전체 맥락이 필요해 보관한다. */
	@Lob
	@Column(name = "extracted_text")
	private String extractedText;

	/** vectorStore.add()에서 청크마다 부여된 ID. 파일 삭제 시 이 ID로 벡터스토어에서도 정확히 지운다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "chunk_ids")
	private List<String> chunkIds;

	/** 사용자가 마스킹 검수를 확정한 시각. 검수 없이 처리된 파일(기능 도입 전, 검수 꺼짐)은 null. */
	@Column(name = "masking_confirmed_at")
	private Instant maskingConfirmedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private SourceDocument(UUID handoverId, String fileName, String mimeType, long fileSize, String s3Key) {
		this.handoverId = handoverId;
		this.fileName = fileName;
		this.mimeType = mimeType;
		this.fileSize = fileSize;
		this.s3Key = s3Key;
		this.status = SourceDocumentStatus.EXTRACTING;
	}

	public static SourceDocument create(UUID handoverId, String fileName, String mimeType, long fileSize, String s3Key) {
		return new SourceDocument(handoverId, fileName, mimeType, fileSize, s3Key);
	}

	public void markIndexed(String extractedText, List<String> chunkIds) {
		this.status = SourceDocumentStatus.INDEXED;
		this.extractedText = extractedText;
		this.chunkIds = chunkIds;
		this.updatedAt = Instant.now();
	}

	/** 텍스트 추출과 후보 탐지까지 끝나고 사용자 검수를 기다린다. 이 시점의 extractedText는 마스킹 전 원문이다. */
	public void markMaskingReview(String extractedText) {
		this.status = SourceDocumentStatus.MASKING_REVIEW;
		this.extractedText = extractedText;
		this.updatedAt = Instant.now();
	}

	/**
	 * 사용자가 검수를 확정했다. 원문을 마스킹된 텍스트로 바꾸고 임베딩을 기다린다.
	 * 이후 분석·초안 생성은 이 마스킹된 텍스트만 읽는다.
	 */
	public void confirmMasking(String maskedText) {
		this.status = SourceDocumentStatus.INDEXING;
		this.extractedText = maskedText;
		this.maskingConfirmedAt = Instant.now();
		this.updatedAt = this.maskingConfirmedAt;
	}

	/** 확정된 파일의 임베딩을 (다시) 시작한다. 텍스트는 이미 마스킹되어 있다. */
	public void markIndexing() {
		this.status = SourceDocumentStatus.INDEXING;
		this.updatedAt = Instant.now();
	}

	/** 텍스트는 그대로 두고 인덱싱 완료만 기록한다(확정된 파일용). */
	public void markIndexed(List<String> chunkIds) {
		this.status = SourceDocumentStatus.INDEXED;
		this.chunkIds = chunkIds;
		this.updatedAt = Instant.now();
	}

	public boolean isMaskingConfirmed() {
		return maskingConfirmedAt != null;
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
