package com.baton.ai;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.baton.common.BusinessException;
import com.baton.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 업로드된 원본 파일을 S3에 저장·조회·삭제한다. 인증은 EC2에 붙인 IAM 역할로 처리되므로
 * 액세스 키를 여기 어디에도 두지 않는다.
 */
@Component
@Slf4j
public class S3FileStorage {

	private final S3Client s3Client;
	private final String bucket;

	public S3FileStorage(S3Client s3Client, @Value("${app.s3.bucket}") String bucket) {
		this.s3Client = s3Client;
		this.bucket = bucket;
	}

	public String upload(UUID handoverId, String fileName, String contentType, byte[] content) {
		String key = "handovers/%s/%s-%s".formatted(handoverId, UUID.randomUUID(), fileName);

		try {
			s3Client.putObject(
					PutObjectRequest.builder()
							.bucket(bucket)
							.key(key)
							.contentType(contentType)
							.build(),
					RequestBody.fromBytes(content));
		} catch (S3Exception e) {
			log.error("[*] S3 upload failed for key={}", key, e);
			throw new BusinessException(ErrorCode.AI_FILE_PARSE_FAILED, "파일 저장에 실패했습니다.");
		}

		return key;
	}

	public byte[] download(String key) {
		try {
			ResponseBytes<GetObjectResponse> response = s3Client.getObject(
					GetObjectRequest.builder().bucket(bucket).key(key).build(),
					ResponseTransformer.toBytes());
			return response.asByteArray();
		} catch (S3Exception e) {
			log.error("[*] S3 download failed for key={}", key, e);
			throw new BusinessException(ErrorCode.AI_SOURCE_DOCUMENT_NOT_FOUND);
		}
	}

	public void delete(String key) {
		s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
	}
}
