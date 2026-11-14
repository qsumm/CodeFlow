-- 工作项域新 schema(seekdb 基线 v1)
CREATE TABLE IF NOT EXISTS wi_flow (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(512) DEFAULT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_wi_flow_ws (workspace_id, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_flow_state (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  flow_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(64) NOT NULL,
  category VARCHAR(16) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_wi_flow_state_flow (flow_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_flow_edge (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  flow_id BIGINT UNSIGNED NOT NULL,
  from_state_id BIGINT UNSIGNED NOT NULL,
  to_state_id BIGINT UNSIGNED NOT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wi_flow_edge (flow_id, from_state_id, to_state_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_ticket (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  title VARCHAR(512) NOT NULL,
  body_md MEDIUMTEXT,
  type VARCHAR(16) NOT NULL,
  priority TINYINT NOT NULL DEFAULT 2,
  flow_id BIGINT UNSIGNED DEFAULT NULL,
  state_id BIGINT UNSIGNED DEFAULT NULL,
  assignee_type VARCHAR(16) DEFAULT NULL,
  assignee_id BIGINT DEFAULT NULL,
  squad_id BIGINT DEFAULT NULL,
  sdlc_id BIGINT DEFAULT NULL,
  external_source VARCHAR(32) DEFAULT NULL,
  external_id VARCHAR(128) DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_wi_ticket_ws_state (workspace_id, state_id, deleted_at),
  KEY idx_wi_ticket_ws_modified (workspace_id, gmt_modified),
  KEY idx_wi_ticket_ext (workspace_id, external_source, external_id),
  KEY idx_wi_ticket_assignee (workspace_id, assignee_type, assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_ticket_tag (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  tag VARCHAR(64) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wi_tag (ticket_id, tag),
  KEY idx_wi_tag_ws_tag (workspace_id, tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wi_note (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  author_type VARCHAR(16) NOT NULL,
  author_id BIGINT NOT NULL,
  body_md MEDIUMTEXT NOT NULL,
  meta_json JSON DEFAULT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_wi_note_ticket (ticket_id, gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_note_mention (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  note_id BIGINT UNSIGNED NOT NULL,
  mentioned_type VARCHAR(16) NOT NULL,
  mentioned_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  KEY idx_wi_mention_note (note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_journal (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  actor_type VARCHAR(16) DEFAULT NULL,
  actor_id BIGINT DEFAULT NULL,
  from_val VARCHAR(128) DEFAULT NULL,
  to_val VARCHAR(128) DEFAULT NULL,
  detail_json JSON DEFAULT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_wi_journal_ticket (ticket_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_subscriber (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT NOT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wi_subscriber (ticket_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_note_dispatch (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  note_id BIGINT UNSIGNED NOT NULL,
  target VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  reply_ref VARCHAR(128) DEFAULT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_wi_note_dispatch (ticket_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_exec_control (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  phase VARCHAR(32) NOT NULL,
  actor_id BIGINT DEFAULT NULL,
  detail_json JSON DEFAULT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_wi_exec_control (ticket_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS wi_restart_round (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT UNSIGNED NOT NULL,
  round_no INT NOT NULL,
  reason VARCHAR(64) DEFAULT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wi_restart (ticket_id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
