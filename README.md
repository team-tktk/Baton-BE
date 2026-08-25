# Baton-BE

<!-- TODO: Baton이 어떤 서비스인지 2~3줄로 채우기 -->

## 기술 스택

| 구분 | 사용 | 비고 |
|---|---|---|
| 언어 | Java 21 | Temurin 21 |
| 프레임워크 | Spring Boot 4.1.1 | Spring Framework 7 |
| 빌드 | Gradle 8.14.3 | 래퍼 포함, 별도 설치 불필요 |
| DB | PostgreSQL 17 + pgvector | 일반 테이블 + 임베딩 벡터를 한 DB에 |
| ORM | Spring Data JPA (Hibernate 7) | |
| API 문서 | springdoc-openapi 3.1.0 | Swagger UI |

> ⚠️ **버전 궁합 주의.** Spring Boot 4는 Spring AI **2.x**, springdoc **3.x** 하고만 맞는다.
> 구글에 흔한 Spring AI 1.x / springdoc 2.x 예제를 그대로 가져오면 기동 단계에서 깨진다.

## 빠른 시작

**필요한 것**: JDK 21, Docker Desktop

```bash
git clone git@github.com:team-tktk/Baton-BE.git
cd Baton-BE

cp .env.example .env        # 필요하면 값 수정
docker compose up -d        # DB 기동
docker compose ps           # STATUS가 healthy 될 때까지 대기 (약 10초)
./db/init-extensions.sh     # 최초 1회만: pgvector 등 확장 설치
./gradlew bootRun
```

> DB가 먼저 떠 있어야 한다. 안 그러면 `Connection refused`로 기동 실패한다.

## 주요 주소

| 주소 | 설명 |
|---|---|
| http://localhost:8080/api/health | 헬스 체크 |
| http://localhost:8080/swagger-ui.html | API 문서 (Swagger UI) |
| http://localhost:8080/v3/api-docs | OpenAPI 스펙 JSON |

## 프로젝트 구조

```
src/main/java/com/baton/
├── BatonApplication.java        # 진입점
├── common/
│   └── GlobalExceptionHandler   # 전역 예외 → JSON 응답 변환
├── config/
│   ├── CorsConfig               # 프론트 도메인 허용
│   └── OpenApiConfig            # Swagger 기본 정보
└── health/
    └── HealthController         # 헬스 체크 (샘플)
```

**패키지는 도메인 단위로 나눈다.** 계층별(`controller/`, `service/`에 전부 몰아넣기)이 아니라
기능별로 묶는다. 관련 코드가 한 폴더에 모여서 찾기 쉽고, 나중에 분리하기도 편하다.

```
com.baton.post/
├── PostController.java
├── PostService.java
├── PostRepository.java
├── Post.java              # 엔티티
└── dto/
    ├── PostCreateRequest.java
    └── PostResponse.java
```

## 설정

| 파일 | 용도 |
|---|---|
| `application.yml` | 기본 설정 (로컬 개발 기준) |
| `application-prod.yml` | 운영. Swagger 차단 + `ddl-auto: validate` |
| `application-local.yml` | 개인 설정. **gitignore됨** — 로컬에서만 덮어쓸 때 |
| `.env` | DB 비밀번호, API 키. **gitignore됨** |

운영 실행:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
```

### 알아둘 설정

- `spring.threads.virtual.enabled: true` — Java 21 가상 스레드. 이상 동작하면 `false`로
- `spring.jpa.hibernate.ddl-auto: update` — **개발 전용.** 엔티티 바꾸면 테이블이 자동으로 따라옴.
  팀원이 늘거나 배포하면 Flyway로 갈아타야 한다
- `spring.jpa.open-in-view: false` — 커넥션을 오래 잡지 않도록 끔. 서비스 계층 밖에서
  지연 로딩을 건드리면 예외가 나는데, 이건 설계가 잘못됐다는 신호다
- `app.cors.allowed-origins` — 프론트 주소. 배포 시 실제 도메인 추가

## DB

접속 정보: `localhost:5432` / db `baton` / user `baton` / pw `.env`의 `DB_PASSWORD` (기본 `1234`)

```bash
docker compose exec db psql -U baton -d baton    # psql 접속
docker compose exec db psql -U baton -d baton -c '\dx'   # 설치된 확장 확인
docker compose exec db psql -U baton -d baton -c '\dt'   # 테이블 목록

docker compose logs -f db     # DB 로그
docker compose down           # 중지 (데이터 유지)
docker compose down -v        # 중지 + 데이터 삭제 (초기화)
```

데이터는 named volume `baton-pgdata`에 저장된다. 호스트 폴더를 마운트하지 않는데,
macOS가 `~/Desktop` 등에 대한 Docker 접근을 막기 때문이다.

## RAG 켜기

임베딩 벡터를 별도 벡터DB가 아니라 **같은 Postgres**에 저장한다.
일반 테이블과 조인·트랜잭션이 되므로 권한 필터링이나 데이터 정합성 처리가 단순해진다.

1. `build.gradle` — `spring-ai-starter-vector-store-pgvector` 와 모델 스타터 주석 해제
2. `application.yml` — `spring.ai` 블록 주석 해제
3. `.env` 에 `OPENAI_API_KEY` 추가

> 모델 스타터 없이 vector-store만 켜면 `EmbeddingModel` 빈이 없어서 기동에 실패한다.
> 둘은 항상 같이 켠다. `dimensions` 값은 사용하는 임베딩 모델의 차원과 반드시 일치해야 한다.

## 앞으로 붙일 것

- [ ] Flyway — 스키마 마이그레이션 (`ddl-auto` 졸업)
- [ ] Spring Security + JWT — 로그인 생기면
- [ ] Spring AI — 임베딩 모델 확정되면
- [ ] Actuator — 배포 시 헬스체크/모니터링
