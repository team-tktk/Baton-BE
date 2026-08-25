package com.baton.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class AiConfig {

	@Bean
	public ChatClient chatClient(ChatClient.Builder builder) {
		return builder.build();
	}

	@Bean
	public TokenTextSplitter tokenTextSplitter() {
		return TokenTextSplitter.builder().build();
	}

	/**
	 * Spring Boot 4의 기본 Jackson 자동설정이 com.fasterxml.jackson.databind.ObjectMapper 빈을
	 * 등록해주지 않아 RagAnalysisService가 기동 시점에 못 뜬다. 직접 빈으로 등록한다.
	 */
	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}
}
