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
						.version("v0.0.1")
						.description(API_DESCRIPTION));
	}

	private static final String API_DESCRIPTION = """
			Baton 백엔드 API. 인수인계 문서를 AI로 초안 생성하고, 인수자 질의응답과 관리자 검토까지 다룬다.

			## 공통 규칙

			- **Base path**: 모든 경로는 `/api/v1` 하위. 시간은 ISO-8601(UTC), ID는 UUID.
			- **인증**: HTTP-only 세션 쿠키 기반. `POST /auth/login` 성공 시 세션 쿠키가 발급되고,
			  이후 요청은 브라우저가 쿠키를 자동 전송(`fetch(..., { credentials: 'include' })`)하면 인증된다.
			  미인증 요청은 `401`(code=`AUTH_REQUIRED`).
			- **권한**: URL의 역할 값이 아니라 로그인 사용자와 `HandoverParticipant` 관계로 검사한다.
			  인계자(owner)는 자신이 만든 문서만 수정, 인수자(recipient)는 자신에게 전달된 문서만 조회,
			  관리자(reviewer)는 자신이 지정된 건만 검토/승인한다. 권한 없으면 `403`(code=`HANDOVER_FORBIDDEN`).
			- **에러 형식**: RFC 7807 `ProblemDetail`을 따르며 `code`(기계 판별용, `ErrorCode` 이름)와
			  검증 실패 시 `fieldErrors`를 추가로 담는다. 프론트는 HTTP status가 아니라 `code`로 분기하는 것을 권장.
			- **페이지네이션**: 목록은 `cursor`(직전 페이지 마지막 항목 id)+`size`(기본 20, 최대 100) 키셋 방식.
			  응답의 `nextCursor`/`hasNext`로 다음 페이지를 잇는다.
			- **멱등성**: 제출/수신확인/완료/승인/보완요청은 같은 요청을 반복해도 상태가 중복 변경되지 않는다.

			## 인수인계 상태 흐름

			`DRAFT → ANALYZING → ANSWERING → EDITING → PENDING_REVIEW → (REVISION_REQUESTED ↔ EDITING) → APPROVED → COMPLETED`

			인수자의 수신 여부(`UNREAD`/`READ`)는 본 상태와 별개로 참여자 단위로 관리된다.
			""";
}
