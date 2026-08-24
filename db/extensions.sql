-- 컨테이너 최초 생성 시 1회 실행됨.
-- 이미 볼륨이 있으면 안 돌아가니, 나중에 추가할 땐 psql로 직접 실행할 것.
CREATE EXTENSION IF NOT EXISTS vector;      -- pgvector: VECTOR 타입 + 유사도 연산자
CREATE EXTENSION IF NOT EXISTS hstore;      -- Spring AI가 메타데이터 저장에 사용
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- 문서 ID 생성용
