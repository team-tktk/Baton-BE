-- 준비도 보완: 영역 하나·섹션 하나 단위의 보완안을 "여러 영역·여러 섹션을 한 번에" 보완하는 보완안으로 바꾼다.
-- 운영은 ddl-auto: validate라 스키마를 자동으로 바꾸지 않는다. 새 버전 배포 "전에" 운영 DB에 한 번 실행한다.
-- 로컬(ddl-auto: update)도 실행한다 — 새 컬럼은 자동으로 생기지만 옛 NOT NULL 컬럼(area·section·evidence)이 남아 저장이 실패한다.

BEGIN;

ALTER TABLE readiness_fixes ADD COLUMN IF NOT EXISTS area_results jsonb;
ALTER TABLE readiness_fixes ADD COLUMN IF NOT EXISTS sections jsonb;

-- 옛 보완안은 영역 하나짜리 결과로 옮긴다(이력 조회용). 옛 질문에는 영역이 없으므로 보완안의 영역을 붙인다.
UPDATE readiness_fixes
SET area_results = jsonb_build_array(jsonb_build_object(
        'area', area,
        'status', NULL,
        'sections', jsonb_build_array(section),
        'proposed', after_content IS NOT NULL,
        'changeSummary', change_summary,
        'evidence', evidence)),
    sections = jsonb_build_array(section),
    questions = COALESCE((
        SELECT jsonb_agg(q || jsonb_build_object('area', area, 'options', '[]'::jsonb))
        FROM jsonb_array_elements(questions) AS q), '[]'::jsonb)
WHERE area_results IS NULL;

-- 열려 있던 옛 보완안은 새 흐름(답변 저장 → 보완안 만들기)으로 이어 쓸 수 없으므로 닫는다. 준비도 화면에서 새로 시작하면 된다.
UPDATE readiness_fixes SET status = 'DISCARDED' WHERE status IN ('NEEDS_INPUT', 'PROPOSED');

ALTER TABLE readiness_fixes ALTER COLUMN area_results SET NOT NULL;
ALTER TABLE readiness_fixes ALTER COLUMN sections SET NOT NULL;

ALTER TABLE readiness_fixes DROP COLUMN IF EXISTS area;
ALTER TABLE readiness_fixes DROP COLUMN IF EXISTS section;
ALTER TABLE readiness_fixes DROP COLUMN IF EXISTS change_summary;
ALTER TABLE readiness_fixes DROP COLUMN IF EXISTS evidence;

COMMIT;
