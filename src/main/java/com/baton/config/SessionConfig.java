package com.baton.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/**
 * 세션을 PostgreSQL(SPRING_SESSION 테이블)에 저장한다 → 서버 재시작·다중 인스턴스에도 로그인 유지.
 *
 * 왜 명시적으로 @EnableJdbcHttpSession 을 켜는가:
 * Spring Boot 4는 자동설정을 기술별 모듈로 쪼갰는데, spring-session-jdbc용 스토어 자동설정이
 * 클래스패스에 존재하지 않는다(spring-boot-session은 공통 필터/쿠키만 제공). 그래서 JDBC 세션
 * 저장소는 자동으로 켜지지 않으므로 여기서 직접 활성화하고 스키마도 직접 초기화한다.
 *
 * maxInactiveIntervalInSeconds = 7일. (application.yml의 spring.session.timeout은
 * 자동설정이 없어 무시되므로 값은 여기서 관리한다.)
 */
@Configuration
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 7 * 24 * 60 * 60)
public class SessionConfig {

	/**
	 * 세션 테이블(SPRING_SESSION, SPRING_SESSION_ATTRIBUTES)을 생성한다.
	 * spring-session-jdbc가 번들한 스키마 스크립트를 사용. 이미 있으면 무시(continueOnError).
	 * 운영에선 spring.session.jdbc.initialize-schema=never 로 끄고 마이그레이션으로 관리한다.
	 */
	@Bean
	public DataSourceInitializer sessionSchemaInitializer(
			DataSource dataSource,
			@Value("${spring.session.jdbc.initialize-schema:always}") String initializeSchema) {

		DataSourceInitializer initializer = new DataSourceInitializer();
		initializer.setDataSource(dataSource);
		initializer.setEnabled(!"never".equalsIgnoreCase(initializeSchema));

		ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
		populator.addScript(new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"));
		populator.setContinueOnError(true); // 테이블이 이미 존재하면 넘어간다
		initializer.setDatabasePopulator(populator);

		return initializer;
	}
}
