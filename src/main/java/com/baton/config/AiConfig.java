package com.baton.config;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
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

	/**
	 * Spring AI 2.x의 OpenAI 연동은 spring.http.client.* 설정을 타는 RestClient가 아니라
	 * 내부적으로 OkHttp 기반 OpenAI Java SDK 클라이언트를 직접 만들어 쓴다(spring-ai-openai의
	 * org.springframework.ai.openai.http.okhttp 패키지). 그래서 그 설정으로는 타임아웃이
	 * 전혀 안 걸렸고, 실제로 OpenAI 응답이 멈추면 스레드가 무기한 붙잡혀 분석이 영원히
	 * "진행 중"으로 남는 게 라이브에서 확인됐다(90초 넘게도 안 끊김). OpenAiHttpClientBuilderCustomizer는
	 * OpenAiChatAutoConfiguration이 List<OpenAiHttpClientBuilderCustomizer>로 직접 모아서
	 * OkHttp 클라이언트 빌드에 적용하는 공식 확장 포인트라, 여기로 타임아웃을 걸어야 실제로 먹는다.
	 */
	@Bean
	public OpenAiHttpClientBuilderCustomizer openAiTimeoutCustomizer() {
		return builder -> builder.timeout(Duration.ofSeconds(90));
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
