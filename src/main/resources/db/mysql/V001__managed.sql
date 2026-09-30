CREATE TABLE managed_schema (
    slot TINYINT PRIMARY KEY CHECK (slot = 1),
    version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('INITIALIZING','READY')),
    installation_id VARCHAR(36) NOT NULL,
    script_sha256 CHAR(64) NOT NULL,
    layout_sha256 CHAR(64)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE runtime_owner (
    slot TINYINT PRIMARY KEY CHECK (slot = 1),
    epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO runtime_owner(slot) VALUES (1);
CREATE TABLE capacity_guard (
    slot TINYINT PRIMARY KEY CHECK (slot = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO capacity_guard VALUES (1);
CREATE TABLE administrator (
    slot TINYINT PRIMARY KEY CHECK (slot = 1),
    username VARCHAR(128) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    label VARCHAR(100) NOT NULL CHECK (CHAR_LENGTH(label) BETWEEN 1 AND 100),
    enabled TINYINT NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    auth_epoch BIGINT NOT NULL DEFAULT 1 CHECK (auth_epoch > 0),
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE binding (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    identity_namespace VARCHAR(128) NOT NULL,
    account_id VARCHAR(256) NOT NULL,
    bot_id VARCHAR(256) NOT NULL,
    sender_id VARCHAR(256) NOT NULL,
    origin VARCHAR(2048) NOT NULL,
    evidence_revision VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE KEY binding_version (user_id, version),
    UNIQUE KEY binding_scope (id, user_id),
    UNIQUE KEY binding_identity (id, user_id, identity_namespace, account_id),
    FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE binding_connection (
    binding_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    bot_id VARCHAR(256) NOT NULL,
    sender_id VARCHAR(256) NOT NULL,
    PRIMARY KEY (binding_id, user_id, bot_id, sender_id),
    FOREIGN KEY (binding_id, user_id) REFERENCES binding(id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE active_binding (
    user_id VARCHAR(36) PRIMARY KEY,
    binding_id VARCHAR(36) NOT NULL UNIQUE,
    identity_namespace VARCHAR(128) NOT NULL,
    account_id VARCHAR(256) NOT NULL,
    bot_id VARCHAR(256) NOT NULL UNIQUE,
    sender_id VARCHAR(256) NOT NULL,
    UNIQUE KEY active_account (identity_namespace, account_id),
    FOREIGN KEY (binding_id, user_id, identity_namespace, account_id)
        REFERENCES binding(id, user_id, identity_namespace, account_id),
    FOREIGN KEY (binding_id, user_id, bot_id, sender_id)
        REFERENCES binding_connection(binding_id, user_id, bot_id, sender_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE channel_session (
    binding_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token TEXT NOT NULL CHECK (CHAR_LENGTH(token) > 0),
    scan_user_id VARCHAR(256) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    `cursor` TEXT NOT NULL,
    active TINYINT NOT NULL CHECK (active IN (0,1)),
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (binding_id, user_id) REFERENCES binding(id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE binding_attempt (
    sequence BIGINT PRIMARY KEY AUTO_INCREMENT,
    id VARCHAR(36) NOT NULL UNIQUE,
    user_id VARCHAR(36) NOT NULL,
    auth_epoch BIGINT NOT NULL CHECK (auth_epoch > 0),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('INITIAL','REAUTHENTICATE','REPLACE')),
    phase VARCHAR(32) NOT NULL CHECK (phase IN ('REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY','SUCCEEDED','EXPIRED','CANCELLED','CONFLICT','IDENTITY_UNVERIFIED','FAILED')),
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    completed_binding_id VARCHAR(36),
    UNIQUE KEY attempt_scope (id, user_id),
    KEY latest_user_attempt (user_id, sequence),
    FOREIGN KEY (user_id) REFERENCES app_user(id),
    FOREIGN KEY (completed_binding_id, user_id) REFERENCES binding(id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE active_invitation (
    user_id VARCHAR(36) PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL UNIQUE,
    FOREIGN KEY (attempt_id, user_id) REFERENCES binding_attempt(id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE conversation (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL,
    ended_at BIGINT,
    last_user_message_at BIGINT,
    history_revision BIGINT NOT NULL DEFAULT 0 CHECK (history_revision >= 0),
    confirmed_turn_count BIGINT NOT NULL DEFAULT 0 CHECK (confirmed_turn_count >= 0),
    last_confirmed_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_confirmed_sequence >= 0),
    FOREIGN KEY (binding_id, user_id) REFERENCES binding(id, user_id),
    UNIQUE KEY conversation_scope (id, user_id, binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE active_conversation (
    user_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL UNIQUE,
    PRIMARY KEY (user_id, binding_id),
    FOREIGN KEY (conversation_id, user_id, binding_id) REFERENCES conversation(id, user_id, binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE inbound_event (
    sequence BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    bot_id VARCHAR(256) NOT NULL,
    sender_id VARCHAR(256) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    auth_epoch BIGINT NOT NULL CHECK (auth_epoch > 0),
    message_id VARCHAR(256) NOT NULL CHECK (CHAR_LENGTH(message_id) > 0),
    received_at BIGINT NOT NULL,
    text MEDIUMTEXT,
    context_token TEXT NOT NULL CHECK (CHAR_LENGTH(context_token) > 0),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('CHAT','NEW','HELP','NOTICE')),
    FOREIGN KEY (binding_id, user_id, bot_id, sender_id)
        REFERENCES binding_connection(binding_id, user_id, bot_id, sender_id),
    FOREIGN KEY (conversation_id, user_id, binding_id) REFERENCES conversation(id, user_id, binding_id),
    UNIQUE KEY inbound_dedup (bot_id, message_id),
    UNIQUE KEY event_scope (sequence, user_id, binding_id, conversation_id),
    KEY history_window (user_id, binding_id, conversation_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE turn (
    event_sequence BIGINT PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    runtime_epoch BIGINT NOT NULL CHECK (runtime_epoch > 0),
    stage VARCHAR(24) NOT NULL CHECK (stage IN ('RECEIVED','PROCESSING','RESPONSE_READY','SENDING','SENT','FAILED','INTERRUPTED','SEND_FAILED','DELIVERY_UNKNOWN')),
    reply_text MEDIUMTEXT,
    model_succeeded TINYINT NOT NULL DEFAULT 0 CHECK (model_succeeded IN (0,1)),
    client_id VARCHAR(36) UNIQUE,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (event_sequence, user_id, binding_id, conversation_id)
        REFERENCES inbound_event(sequence, user_id, binding_id, conversation_id),
    KEY pending_user_turns (user_id, stage, event_sequence),
    KEY pending_global_turns (stage, event_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE model_configuration (
    version BIGINT PRIMARY KEY AUTO_INCREMENT,
    configuration_json MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE admin_setting (
    slot TINYINT PRIMARY KEY CHECK (slot = 1),
    idle_minutes BIGINT NOT NULL DEFAULT 30 CHECK (idle_minutes BETWEEN 1 AND 1440),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    model_version BIGINT,
    FOREIGN KEY (model_version) REFERENCES model_configuration(version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO admin_setting(slot) VALUES (1);
CREATE TABLE model_attempt (
    id VARCHAR(36) PRIMARY KEY,
    event_sequence BIGINT NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    binding_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    runtime_epoch BIGINT NOT NULL CHECK (runtime_epoch > 0),
    model_version BIGINT NOT NULL,
    attempt_number BIGINT NOT NULL CHECK (attempt_number > 0),
    started_at BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    FOREIGN KEY (event_sequence, user_id, binding_id, conversation_id)
        REFERENCES inbound_event(sequence, user_id, binding_id, conversation_id),
    FOREIGN KEY (model_version) REFERENCES model_configuration(version),
    UNIQUE KEY event_attempt (event_sequence, attempt_number),
    KEY attempt_reporting (user_id, conversation_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE model_usage (
    attempt_id VARCHAR(36) PRIMARY KEY,
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    cached_input_tokens BIGINT CHECK (cached_input_tokens >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('REPORTED','PARTIAL','UNKNOWN','INVALID')),
    CHECK (input_tokens IS NULL OR cached_input_tokens IS NULL OR cached_input_tokens <= input_tokens),
    FOREIGN KEY (attempt_id) REFERENCES model_attempt(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE audit_event (
    sequence BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(128) NOT NULL,
    target VARCHAR(256) NOT NULL,
    result VARCHAR(64) NOT NULL,
    occurred_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
