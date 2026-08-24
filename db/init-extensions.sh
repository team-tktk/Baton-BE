#!/usr/bin/env bash
# DB 최초 기동 후 1회만 실행하면 됨.
#   ./db/init-extensions.sh
set -e
docker compose exec -T db psql -U baton -d baton <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;      -- pgvector: VECTOR 타입 + 유사도 연산자
CREATE EXTENSION IF NOT EXISTS hstore;      -- Spring AI 메타데이터 저장용
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; -- 문서 ID 생성용
SQL
echo "--- 설치된 확장 ---"
docker compose exec -T db psql -U baton -d baton -c '\dx'
