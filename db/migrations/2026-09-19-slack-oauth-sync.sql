BEGIN;
CREATE TABLE IF NOT EXISTS slack_connections (id uuid PRIMARY KEY, owner_id uuid NOT NULL, team_id varchar(255) NOT NULL, team_name varchar(255) NOT NULL, user_token varchar(2000) NOT NULL, slack_user_id varchar(255) NOT NULL, scopes varchar(255) NOT NULL, created_at timestamptz NOT NULL, UNIQUE(owner_id,team_id));
CREATE TABLE IF NOT EXISTS slack_oauth_states (state varchar(255) PRIMARY KEY, owner_id uuid NOT NULL, expires_at timestamptz NOT NULL);
CREATE TABLE IF NOT EXISTS slack_subscriptions (id uuid PRIMARY KEY, handover_id uuid NOT NULL, connection_id uuid NOT NULL, channel_id varchar(255) NOT NULL, channel_name varchar(255) NOT NULL, oldest_ts varchar(255), backfill_cursor varchar(2000), backfill_complete boolean NOT NULL DEFAULT false, enabled boolean NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(handover_id,connection_id,channel_id));
CREATE TABLE IF NOT EXISTS slack_imported_messages (id uuid PRIMARY KEY, handover_id uuid NOT NULL, connection_id uuid NOT NULL, channel_id varchar(255) NOT NULL, message_ts varchar(255) NOT NULL, source_id uuid NOT NULL, content_hash varchar(255) NOT NULL, UNIQUE(handover_id,connection_id,channel_id,message_ts));
CREATE INDEX IF NOT EXISTS idx_slack_subscriptions_enabled ON slack_subscriptions(enabled);
COMMIT;
