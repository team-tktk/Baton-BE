package com.baton.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 클라이언트 설정. 액세스 키를 코드/설정에 넣지 않고 EC2에 붙인 IAM 역할로 인증한다
 * (DefaultCredentialsProvider가 인스턴스 메타데이터에서 자동으로 자격증명을 가져옴).
 */
@Configuration
public class S3Config {

	@Bean
	public S3Client s3Client(@Value("${app.s3.region}") String region) {
		return S3Client.builder()
				.region(Region.of(region))
				.credentialsProvider(DefaultCredentialsProvider.create())
				.build();
	}
}
