-- AI 사용량 보호: 요청 기록, 운영 중 조정하는 한도값, 같은 인수인계 AI 작업 중복 실행 잠금.
-- 한도는 인수인계서 생성·보완안 생성·채팅 요청을 합산해서 센다(feature는 어떤 기능을 썼는지 기록용).
-- 운영은 ddl-auto: validate라 스키마를 자동으로 바꾸지 않는다. 새 버전 배포 "전에" 운영 DB에 한 번 실행한다.
-- 로컬(ddl-auto: update)은 테이블이 자동으로 만들어지므로 실행하지 않아도 된다(실행해도 무해).

BEGIN;

-- 한도 확인을 통과한 AI 요청 1건 = 1행. 서버 여러 대가 이 테이블로 함께 센다. 오래된 행은 서버가 주기적으로 지운다.
CREATE TABLE IF NOT EXISTS ai_usage_events (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL,
    organization_key varchar(255),
    feature          varchar(30) NOT NULL
        CHECK (feature IN ('DRAFT_GENERATION', 'READINESS_FIX', 'CHAT')),
    handover_id      uuid,
    created_at       timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_events_user_created
    ON ai_usage_events (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_events_org_created
    ON ai_usage_events (organization_key, created_at);

-- 한도값(scope당 한 행). NULL = 해당 기간 제한 없음, enabled=false = 해당 scope 한도 끔.
CREATE TABLE IF NOT EXISTS ai_usage_limits (
    scope      varchar(20) PRIMARY KEY CHECK (scope IN ('USER', 'ORGANIZATION')),
    enabled    boolean NOT NULL,
    per_minute integer CHECK (per_minute IS NULL OR per_minute > 0),
    per_hour   integer CHECK (per_hour IS NULL OR per_hour > 0),
    per_day    integer CHECK (per_day IS NULL OR per_day > 0),
    updated_at timestamp(6) with time zone NOT NULL
);

-- 초기값: 분 10 / 시간 100 / 하루 200 (세 기능 합산).
-- 조직(팀) 한도는 팀 이름이 회원가입 자유입력이라 믿을 수 없어 꺼 둔다. 이미 조정한 값이 있으면 덮어쓰지 않는다.
INSERT INTO ai_usage_limits (scope, enabled, per_minute, per_hour, per_day, updated_at)
VALUES ('USER', true, 10, 100, 200, now()),
       ('ORGANIZATION', false, 10, 100, 200, now())
ON CONFLICT (scope) DO NOTHING;

-- 실행 중인 AI 작업 잠금. lock_key = 작업:인수인계id. expires_at이 지나면 다음 요청이 가져간다.
CREATE TABLE IF NOT EXISTS ai_task_locks (
    lock_key    varchar(100) PRIMARY KEY,
    owner_token varchar(36) NOT NULL,
    acquired_at timestamp(6) with time zone NOT NULL,
    expires_at  timestamp(6) with time zone NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_task_locks_expires
    ON ai_task_locks (expires_at);

COMMIT;

-- ── 운영 중 한도 조정(배포 불필요, 30초 안에 모든 서버에 반영) ──────────────
-- 현재 값 보기:
--   SELECT * FROM ai_usage_limits;
-- 예) 사용자 하루 한도를 500으로:
--   UPDATE ai_usage_limits SET per_day = 500, updated_at = now() WHERE scope = 'USER';
-- 예) 분 단위 제한만 없애기:
--   UPDATE ai_usage_limits SET per_minute = NULL, updated_at = now() WHERE scope = 'USER';
-- 예) 사용자 한도 끄기:
--   UPDATE ai_usage_limits SET enabled = false, updated_at = now() WHERE scope = 'USER';
-- 기능별 사용량 보기(오늘):
--   SELECT feature, count(*) FROM ai_usage_events WHERE created_at > now() - interval '1 day' GROUP BY feature;
-- 한도 기능 전체를 끄려면 환경변수 AI_USAGE_LIMIT_ENABLED=false (재시작 필요).
