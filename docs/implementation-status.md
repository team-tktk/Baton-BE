# 백엔드 구현 현황 (develop 기준)

- 대조 문서: API 설계 분석서
- 확인일: 2026-08-25
- 범례: ✅ 구현 · 🟡 부분/차이 있음 · ❌ 미구현 · ⛔ 불필요

## 요약

| 영역 | 상태 |
| --- | --- |
| 인증/세션·Security | ✅ 구현 (분석서의 "미구현"은 옛 스냅샷) |
| 인수인계 기본 CRUD | ✅ 구현 |
| 역할별 목록(sent/received/reviews) | ❌ |
| 파일 업로드+RAG 인덱싱 | ✅ / 나머지 파일 API ❌ |
| AI 분석·질문·초안 | 🟡 동기 방식으로 구현(비동기 job/폴링 없음) |
| 문서 자동저장·제출·수신확인 | ❌ |
| 관리자 검토 전체 | ❌ |
| 알림 | ❌ |
| CI / OpenAI 키 연결 | ✅ |

---

## 2. 공통 규칙

| 규칙 | 상태 | 비고 |
| --- | --- | --- |
| Base path `/api/v1` | ✅ | |
| 세션 쿠키 인증 | ✅ | Spring Session JDBC |
| ISO8601 / UTC 저장 | ✅ | `Instant` |
| ID UUID | ✅ | |
| ProblemDetail + `code` + `fieldErrors` | ✅ | GlobalExceptionHandler |
| 페이지네이션 `cursor`/`size` | ❌ | 목록 API가 아직 없음 |
| 문서 `version` 불일치 시 409 | ❌ | 문서 편집 API 미구현 |
| 제출/승인/보완 멱등성 | ❌ | 해당 API 미구현 |

## 3.1 인증과 사용자

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | POST | `/auth/signup` | ✅ | name/team/email/password |
| P0 | POST | `/auth/login` | ✅ | |
| P0 | POST | `/auth/logout` | ✅ | |
| P0 | GET | `/users/me` | 🟡 | 경로가 `/api/v1/auth/me`. 응답에 organization/roles 없음(id/email/name/team만) |
| P0 | GET | `/members?query=` | ❌ | 인수자 선택용 구성원 검색 없음 |
| P1 | POST | `/auth/refresh` | ⛔ | 세션 방식이라 불필요 |

## 3.2 인수인계 기본 정보와 목록

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | POST | `/handovers` | ✅ | status=DRAFT, viewerRole 반환 |
| P0 | PATCH | `/handovers/{id}` | ✅ | 변경 필드만, DRAFT 한정 |
| P0 | GET | `/handovers/{id}` | ✅ | 참여자/업무범위/권한(viewerRole) |
| P0 | GET | `/handovers/sent` | ❌ | |
| P0 | GET | `/handovers/received` | ❌ | |
| P0 | GET | `/handovers/reviews` | ❌ | |
| P1 | DELETE | `/handovers/{id}` | ✅ | DRAFT 한정 (문서상 P1인데 선구현) |

- 상태 enum: `DRAFT~COMPLETED` 전부 정의됨. 실제 전이(제출/승인 등)는 API 미구현이라 아직 DRAFT만 사용.
- 수신상태 `UNREAD/READ`: 참여자 엔티티에 필드 있음. 전이 API(acknowledge)는 ❌.

## 3.3 첨부 파일

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | POST | `/handovers/{id}/files` | ✅ | 업로드→텍스트추출→벡터 인덱싱 |
| P0 | GET | `/handovers/{id}/files` | ❌ | 목록 조회 없음 |
| P0 | DELETE | `.../files/{fileId}` | ❌ | |
| P0 | GET | `.../files/{fileId}/download` | ❌ | |
| P1 | POST | `.../files/{fileId}/retry` | ❌ | |

- 파일 상태 enum(`SourceDocumentStatus`): EXTRACTING/INDEXED/FAILED (분석서의 UPLOADED는 없음).
- 파일 크기: `application.yml` 이미 **50MB로 통일**됨 (분석서의 20MB 불일치 해소).

## 3.4 AI 분석 · 보완 질문 · 초안

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | POST | `/handovers/{id}/analysis` | 🟡 | **동기 실행**. jobId/QUEUED 없이 즉시 초안+질문 생성 |
| P0 | GET | `/handovers/{id}/analysis` | ❌ | 진행률 폴링 없음(비동기 job 모델 없음) |
| P0 | GET | `/handovers/{id}/questions` | ✅ | |
| P0 | PUT | `.../questions/{qid}/answer` | ✅ | answer + skipped |
| P0 | POST | `/questions/complete` | ✅ | 답변 반영해 초안 재생성 |
| P1 | POST | `/analysis/retry` | ❌ | |
| P1 | GET | `/handovers/{id}/sources` | ❌ | |

## 3.5 문서 작성 · 자동 저장 · 제출

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | GET | `/handovers/{id}/document` | 🟡 | 조회는 됨(`HandoverDraft` 기반). 구조화 섹션/version 모델은 제한적 |
| P0 | PATCH | `/handovers/{id}/document` | ❌ | 필드단위 자동저장 없음 |
| P0 | POST | `/handovers/{id}/submit` | ❌ | |
| P0 | POST | `/handovers/{id}/acknowledge` | ❌ | |
| P1 | POST | `/handovers/{id}/complete` | ❌ | |
| P1 | GET | `/handovers/{id}/versions` | ❌ | |
| P1 | GET | `/export?format=markdown` | ❌ | |

## 3.6 인수자용 요약 · AI 질문

| P | Method | Path | 상태 | 비고 |
| --- | --- | --- | --- | --- |
| P0 | GET | `/handovers/{id}/briefing` | ❌ | 첫날 요약 화면 |
| P0 | GET | `/chat/messages` (내역) | ❌ | 대화 저장/조회 없음(POST만 있음) |
| P0 | POST | `/chat/messages` | ✅ | grounded/citations/fallbackContact 구조 분석서와 일치 |
| P0 | GET | `/sources/{sourceId}` | ❌ | 근거 원문 열기 |
| P1 | PATCH | `/tasks/{taskId}` | ❌ | 업무 진행상태 갱신 |

## 3.7 관리자 검토 — 전부 ❌

`GET /review`, `PATCH /review/checklist`, `GET/POST /comments`, `PATCH/DELETE /comments/{id}`, `POST /request-revision`, `POST /approve` 모두 미구현.

## 3.8 알림 — 전부 ❌

`GET /notifications`, `PATCH /notifications/{id}/read`, `PATCH /read-all` 모두 미구현.

## 7. 도메인 모델 현황

| 엔티티 | 상태 |
| --- | --- |
| User | ✅ |
| Handover / HandoverParticipant / WorkScope | ✅ |
| SourceDocument | ✅ |
| ClarificationQuestion | ✅ (답변은 질문 내부 처리, 별도 `ClarificationAnswer` 없음) |
| HandoverDraft | ✅ (분석서의 `HandoverDocument`에 대응, `DocumentVersion` 없음) |
| Organization / Team / UserRole | ❌ (팀은 User의 문자열 필드) |
| HandoverTask | ❌ |
| Attachment(SourceDocument와 분리) | ❌ |
| AnalysisJob | ❌ (분석이 동기라 없음) |
| ChatThread / ChatMessage | ❌ (대화 미저장) |
| Citation(엔티티) | ❌ (DTO만 존재) |
| ReviewChecklist / ReviewComment / Notification | ❌ |

## 6. 인프라/설정

| 항목 | 상태 |
| --- | --- |
| CORS | ✅ (`localhost:3000,5173` — 실제 FE 배포주소 반영은 남음) |
| Swagger/OpenAPI | ✅ |
| 전역 검증 오류 처리 | ✅ |
| Spring Security / Spring AI 의존성 | ✅ 활성(주석 아님) |
| 파일 50MB 통일 | ✅ |
| CI(PR 빌드 검증) | ✅ compileJava/compileTestJava만 |
| OpenAI 키 연결 | ✅ |
| 파일 저장소(S3/R2) | ❌ (현재 원문 바이너리 미보관, 메타/벡터만) |
