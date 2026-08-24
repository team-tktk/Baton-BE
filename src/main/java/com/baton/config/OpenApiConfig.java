package com.baton.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI batonOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Baton API")
						.description("Baton 백엔드 API 문서")
						.version("v0.0.1"));
	}
}
