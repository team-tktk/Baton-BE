package com.baton.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
