package com.baton.aiusage;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiUsageProperties.class)
public class AiUsageConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
