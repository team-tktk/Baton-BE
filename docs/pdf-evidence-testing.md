# PDF 근거 기능: push 전 검증

## 1. 자동 검증 다시 실행

현재 `feat/pdf-evidence-highlights` 브랜치에서 PowerShell로 실행한다.

```powershell
.\gradlew.bat test --tests 'com.baton.ai.*' --tests 'com.baton.masking.*' --tests 'com.baton.readiness.*' --rerun-tasks --no-daemon
```

테스트는 DB·S3·OpenAI 없이 실행한다. 권한 테스트는 컨트롤러/서비스 단위 검증이며, 실제 로그인 쿠키·HTTP 필터·DB 저장·외부 API 호출까지 검증한 것은 아니다.

- 테스트 리포트: `build/reports/tests/test/index.html`
- 업로드 샘플: `build/pdf-evidence-verification/sample.pdf`
- 실제 추출 응답: `build/pdf-evidence-verification/evidence.json`
- PDF 2페이지에 서버 좌표를 겹친 이미지: `build/pdf-evidence-verification/page-2-highlight.png`

샘플 1페이지는 `Introduction.`, 2페이지는 `Check coupon dates first.`이다. 결과 이미지는 프론트 화면이 아니라 PDFBox로 렌더링한 검증 이미지다. 일반 수평 영문 PDF의 좌표를 확인하는 작은 샘플이므로 실제 한글 업무 PDF도 추가로 확인한다.

## 2. 현재 브랜치의 서버 실행

운영 서버는 이 브랜치를 아직 배포하지 않았다. 먼저 로컬에서 현재 브랜치를 실행한다. 기존 IntelliJ 실행 설정에 DB·OpenAI·S3 설정이 있다면 그 설정을 사용하고 `MASKING_ENABLED=true`를 추가한다.

필요한 실행 조건:

- PostgreSQL + pgvector 연결 및 테스트 DB. 저장소의 Docker 구성을 사용한다면 `docker compose up -d db`.
- `OPENAI_API_KEY`, `S3_BUCKET`, 필요 시 `S3_REGION`, DB 접속 설정.
- 로컬에서 S3에 접근 가능한 기존 AWS 자격증명 설정. EC2 IAM 역할은 PC에 자동으로 적용되지 않는다.
- 이 브랜치의 `pdf_text_locations` 컬럼. 기본 `ddl-auto=update` 환경은 시작 시 생성하며, `validate/none`이면 `db/migrations/2026-09-19-pdf-evidence.sql`을 테스트 DB에 먼저 적용한다.

환경변수가 이미 설정된 PowerShell에서는:

```powershell
$env:MASKING_ENABLED = 'true'
.\gradlew.bat bootRun
```

Spring Boot가 `.env` 파일을 자동으로 읽는다고 가정하지 않는다. 비밀값은 채팅이나 리포트에 복사하지 않는다.

## 3. Swagger에서 최소 기능 확인

`http://localhost:8080/swagger-ui.html`을 열고 각 요청의 Try it out → Execute를 사용한다. 포트를 바꿨다면 해당 포트를 사용한다.

1. `POST /api/v1/auth/login`으로 테스트 계정에 로그인한다. 계정이 없으면 먼저 signup한다. 이 서비스는 세션 쿠키 인증이다.
2. 로그인 후 `GET /api/v1/auth/me`를 실행해 로그인과 CSRF 쿠키를 확인한다. POST가 CSRF 403이면 페이지를 새로고침한 뒤 다시 실행한다.
3. `POST /api/v1/handovers`에 아래 본문을 보내고 응답의 `id`를 `handoverId`로 기록한다.

```json
{"title":"PDF evidence QA","recipientIds":[],"reviewerIds":[],"workScopes":[]}
```

4. `POST /api/v1/handovers/{handoverId}/files`에서 생성한 `sample.pdf`를 선택한다. 응답의 **`sourceDocumentId`를 이후 `fileId`와 `sourceId` 모두에 사용**한다. 기대 상태는 `MASKING_REVIEW`.
5. `GET /api/v1/handovers/{handoverId}/files/{fileId}/masking`으로 추출 본문을 확인한다. 이 샘플은 민감정보가 없어 후보가 없는 것이 정상이다.
6. `POST /api/v1/handovers/{handoverId}/files/{fileId}/masking/confirm`을 실행한다. 이때 실제 OpenAI 임베딩 호출이 발생한다. 성공하면 `INDEXED`가 된다.
7. `GET /api/v1/handovers/{handoverId}/sources/{sourceId}/evidence`에 quote로 **`Check coupon dates first.`**를 넣는다.

통과 기준:

| 필드 | 기대값 |
| --- | --- |
| page | 2 |
| quote | Check coupon dates first. |
| highlights | 1개 이상의 좌표 |
| highlights[].page | 2 |
| x, y, width, height | 0~1 범위, width/height는 양수 |
| fileId / sourceId | 업로드 응답의 sourceDocumentId |

이 조회에는 추가 AI 호출이 없다. 먼저 이 단계가 통과해야 채팅 결과도 판단하기 쉽다.

8. 같은 endpoint에 `This sentence does not exist.`를 넣는다. 기대값은 `page=null`, `quote=null`, `highlights=[]`이다.
9. `GET /api/v1/handovers/{handoverId}/files/{fileId}/download`로 원본이 열리는지, 실제 2페이지에 해당 문장이 있는지 확인한다.

## 4. 채팅에서 citation까지 확인

`POST /api/v1/handovers/{handoverId}/chat/messages`에 아래 본문을 보낸다. 실제 임베딩 검색·채팅 호출이 발생한다. 이 API 검증에는 초안 생성이나 준비도 평가를 먼저 실행할 필요가 없다.

```json
{"question":"According to the document, what should I check first for coupons?"}
```

`grounded=true`인 답변의 `citations`에서 샘플 PDF의 `page=2`, coupon 문구가 포함된 `quote`, 좌표를 확인한다. 이후 `GET /chat/messages`에서도 해당 값이 유지되어야 한다. AI가 근거를 찾지 못해 일반 지식으로 답했다면 직접 evidence 조회 결과와 채팅 응답을 구분해 기록한다. citation은 검색 컨텍스트 단위이며 답변 문장별 인용 연결은 아직 아니다.

## 5. 마스킹으로 위치가 바뀌어도 유지되는지

새 인수인계에 샘플을 다시 업로드한다. 확정 전에 아래를 진행한다.

1. `GET /files/{fileId}/masking`의 `text`에서 `Introduction.`이 시작하는 위치를 찾는다. 이 샘플은 보통 0이지만 반드시 실제 text 기준으로 확인한다.
2. `POST /files/{fileId}/masking/candidates`에 아래 본문을 보낸다. 시작이 0이 아니라면 시작·끝 모두 그 차이만큼 더한다.

```json
{"startOffset":0,"endOffset":13,"type":"CUSTOM"}
```

3. 검수를 confirm한다. `Introduction.`은 `[비공개#1]`로 바뀌어 전체 본문 길이가 달라진다.
4. 2페이지 coupon 문장을 evidence 조회했을 때 **여전히 page=2이고 좌표가 있어야 한다.**
5. `Introduction.` 원문을 evidence 조회하면 `quote=null`이어야 한다. 기존 다운로드는 원본 제공 기능이므로 원본 PDF에는 원래 문장이 보이는 것이 정상이다.

## 6. 실제 HTTP 권한 확인

- 로그아웃 또는 시크릿 창의 미로그인 상태에서 evidence와 download 요청 → 401.
- 해당 인수인계에 참여하지 않은 다른 테스트 계정으로 요청 → 403.
- 본인이 소유한 다른 인수인계 ID와 이번 파일 ID를 섞어서 요청 → 404.

## 7. 실제 문서로 추가 확인 후 push

텍스트가 선택되는 한글 PDF를 새로 올리고, 2페이지 이후의 고유한 문장을 정확히 복사해 evidence 조회한다. 문서상 페이지 번호와 응답 page가 같아야 한다. 회전 PDF는 페이지·인용문만 제공할 수 있으며 텍스트 없는 스캔 PDF의 OCR은 지원하지 않는다.

최소 통과선은 샘플 업로드·확정·evidence 조회·다운로드·채팅 이력 저장, 마스킹 후 위치 유지, 다른 계정 접근 차단이다. 프론트 drawer/모바일 표시 검증은 Baton-FE 연결 후 별도로 한다.

```powershell
git push -u origin feat/pdf-evidence-highlights
```

문제가 있으면 실패한 단계, HTTP 상태, 오류 code와 테스트 자료의 evidence 응답을 공유한다. 로그인 쿠키·API 키·비밀번호는 공유하지 않는다.
