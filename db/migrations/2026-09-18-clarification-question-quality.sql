-- 확인 질문 개선: 반영 위치·중요도·반영 시각 컬럼 추가, 상태에 모름·해당 없음·나중에 답하기 추가.
-- 운영은 ddl-auto: validate라 스키마를 자동으로 바꾸지 않는다. 새 버전 배포 "전에" 운영 DB에 한 번 실행한다.
-- 로컬(ddl-auto: update)은 컬럼은 자동 추가되지만 status CHECK 제약은 갱신되지 않으므로 로컬에도 실행한다.

BEGIN;

ALTER TABLE clarification_questions ADD COLUMN IF NOT EXISTS target_sections jsonb;
ALTER TABLE clarification_questions ADD COLUMN IF NOT EXISTS priority integer;
ALTER TABLE clarification_questions ADD COLUMN IF NOT EXISTS applied_at timestamp(6) with time zone;

-- Hibernate가 enum 컬럼에 만든 CHECK 제약(PENDING·ANSWERED·SKIPPED)을 새 값 목록으로 교체한다.
ALTER TABLE clarification_questions DROP CONSTRAINT IF EXISTS clarification_questions_status_check;

-- 기존 "건너뛰기"는 나중에 답하기로 옮긴다.
UPDATE clarification_questions SET status = 'DEFERRED' WHERE status = 'SKIPPED';

ALTER TABLE clarification_questions ADD CONSTRAINT clarification_questions_status_check
    CHECK (status IN ('PENDING', 'ANSWERED', 'UNKNOWN', 'NOT_APPLICABLE', 'DEFERRED'));

-- 이미 처리된 기존 질문은 예전 방식(초안 전체 생성)으로 문서에 반영된 상태로 본다. 반영 API가 옛 답변을 다시 반영하지 않게 한다.
UPDATE clarification_questions q
SET applied_at = d.updated_at
FROM handover_drafts d
WHERE d.handover_id = q.handover_id
  AND q.status = 'ANSWERED'
  AND q.applied_at IS NULL;

COMMIT;
