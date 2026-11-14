-- seekdb 新 schema v1:身份/调度/智能体域(生成器产出)

CREATE TABLE IF NOT EXISTS id_user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  nickname VARCHAR(64),
  email VARCHAR(128),
  password_hash VARCHAR(255),
  is_admin TINYINT DEFAULT 0,
  status VARCHAR(16) DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_name (username),
  KEY idx_id_user_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_user_setting (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  setting_key VARCHAR(128) NOT NULL,
  value_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_setting (user_id,
  setting_key),
  KEY idx_id_user_setting_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_user_im (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  channel VARCHAR(32) NOT NULL,
  external_id VARCHAR(128) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_im (channel,
  external_id),
  KEY idx_id_user_im_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_org (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  owner_id BIGINT,
  description VARCHAR(512),
  background VARCHAR(512),
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_org_owner (owner_id),
  KEY idx_id_org_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_org_member (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  org_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) DEFAULT 'MEMBER',
  identity_tags_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_org_member (org_id,
  user_id),
  KEY idx_id_org_member_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_org_invite (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  org_id BIGINT NOT NULL,
  email VARCHAR(128),
  token VARCHAR(64) NOT NULL,
  status VARCHAR(16) DEFAULT 'PENDING',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_org_invite_token (token),
  KEY idx_id_org_invite_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_ws_role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  role_key VARCHAR(64) NOT NULL,
  name VARCHAR(128),
  permissions_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ws_role (workspace_id,
  role_key),
  KEY idx_id_ws_role_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_ws_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(16) DEFAULT 'PENDING',
  decided_by BIGINT,
  decided_at DATETIME,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ws_req (workspace_id,
  status),
  KEY idx_id_ws_request_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_idp (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL,
  metadata_json JSON,
  status VARCHAR(16) DEFAULT 'ACTIVE',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_id_idp_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_idp_connection (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  idp_id BIGINT NOT NULL,
  entity_id VARCHAR(255),
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_idp_conn (idp_id),
  KEY idx_id_idp_connection_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS id_fed_identity (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  idp_id BIGINT NOT NULL,
  external_subject VARCHAR(255) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_fed_identity (idp_id,
  external_subject),
  KEY idx_id_fed_identity_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_run (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  agent_id BIGINT,
  executor_id BIGINT,
  sdlc_id BIGINT,
  attempt INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL,
  error VARCHAR(512),
  started_at DATETIME,
  ended_at DATETIME,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_run_ticket (ticket_id,
  status),
  KEY idx_rt_run_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_checkpoint (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  step_id VARCHAR(64),
  payload_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rt_checkpoint (run_id,
  step_id),
  KEY idx_rt_checkpoint_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  seq INT NOT NULL,
  event_type VARCHAR(48),
  step_id VARCHAR(64),
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_event_run (run_id,
  seq),
  KEY idx_rt_event_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_ai_usage (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT,
  agent_id BIGINT,
  model VARCHAR(64),
  input_tokens BIGINT DEFAULT 0,
  output_tokens BIGINT DEFAULT 0,
  cost_micros BIGINT DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_usage_run (run_id),
  KEY idx_rt_ai_usage_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_recovery (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  action VARCHAR(32),
  actor_id BIGINT,
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_recovery_run (run_id),
  KEY idx_rt_recovery_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  run_id BIGINT,
  kind VARCHAR(32) NOT NULL,
  status VARCHAR(16) DEFAULT 'PENDING',
  body_json JSON,
  answer_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_request_ticket (ticket_id,
  status),
  KEY idx_rt_request_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_decision (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  decision VARCHAR(32) NOT NULL,
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_decision_run (run_id),
  KEY idx_rt_decision_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_model_choice (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  run_id BIGINT NOT NULL,
  provider VARCHAR(32),
  model VARCHAR(64),
  reason VARCHAR(255),
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_model_run (run_id),
  KEY idx_rt_model_choice_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_guidance (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  run_id BIGINT,
  body_md MEDIUMTEXT,
  status VARCHAR(16) DEFAULT 'OPEN',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_guidance_ticket (ticket_id,
  status),
  KEY idx_rt_guidance_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS rt_outbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  payload_json JSON,
  status VARCHAR(16) DEFAULT 'PENDING',
  retry_count INT DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_rt_outbox_status (status,
  id),
  KEY idx_rt_outbox_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_agent (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  role_code VARCHAR(64),
  role_name VARCHAR(128),
  soul_md MEDIUMTEXT,
  status VARCHAR(16) DEFAULT 'ACTIVE',
  version INT DEFAULT 0,
  dummy TINYINT DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_agent_ws (workspace_id,
  status),
  KEY idx_ag_agent_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_version (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  snapshot_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_version (agent_id,
  version_no),
  KEY idx_ag_version_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_env_ref (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  env_key VARCHAR(128) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_env (agent_id,
  env_key),
  KEY idx_ag_env_ref_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_repo_perm (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  repo_id BIGINT NOT NULL,
  perm VARCHAR(16) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_repo (agent_id,
  repo_id),
  KEY idx_ag_repo_perm_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_skill_ref (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_skill (agent_id,
  skill_id),
  KEY idx_ag_skill_ref_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_memory_ref (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  perm VARCHAR(16),
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_memory (agent_id,
  store_id),
  KEY idx_ag_memory_ref_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_chat (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  title VARCHAR(255),
  user_id BIGINT,
  status VARCHAR(16) DEFAULT 'OPEN',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_chat_agent (agent_id,
  status),
  KEY idx_ag_chat_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_chat_turn (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  chat_id BIGINT NOT NULL,
  seq INT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content_md MEDIUMTEXT,
  delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_turn_chat (chat_id,
  seq),
  KEY idx_ag_chat_turn_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_chat_turn_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  turn_id BIGINT NOT NULL,
  event_type VARCHAR(32),
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_turn_event (turn_id),
  KEY idx_ag_chat_turn_event_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_chat_elicitation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  chat_id BIGINT NOT NULL,
  turn_id BIGINT,
  question_json JSON,
  status VARCHAR(16) DEFAULT 'PENDING',
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_elicit (chat_id,
  status),
  KEY idx_ag_chat_elicitation_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_chat_usage (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  chat_id BIGINT NOT NULL,
  tokens_in BIGINT DEFAULT 0,
  tokens_out BIGINT DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_chat_usage (chat_id),
  KEY idx_ag_chat_usage_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_executor (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  agent_id BIGINT NOT NULL,
  name VARCHAR(128),
  client_kind VARCHAR(32),
  token_hash VARCHAR(128),
  status VARCHAR(16) DEFAULT 'OFFLINE',
  last_heartbeat DATETIME,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_exec_token (token_hash),
  KEY idx_ag_executor_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_executor_update (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  executor_id BIGINT NOT NULL,
  target_version VARCHAR(32),
  status VARCHAR(16),
  detail_json JSON,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_exec_update (executor_id,
  status),
  KEY idx_ag_executor_update_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_mcp_token (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  user_id BIGINT,
  token_hash VARCHAR(128),
  name VARCHAR(128),
  expires_at DATETIME,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ag_mcp_token (token_hash),
  KEY idx_ag_mcp_token_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_ai_session (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  scene VARCHAR(32),
  biz_ref VARCHAR(128),
  user_id BIGINT,
  status VARCHAR(16),
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_ai_session (scene,
  biz_ref),
  KEY idx_ag_ai_session_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS ag_ai_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  workspace_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  seq INT NOT NULL,
  role VARCHAR(16),
  content_md MEDIUMTEXT,
  version INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at DATETIME DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_ag_ai_msg (session_id,
  seq),
  KEY idx_ag_ai_message_modified (gmt_modified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
