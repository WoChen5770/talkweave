CREATE TABLE managed_schema (version INTEGER PRIMARY KEY CHECK(version = 1));
INSERT INTO managed_schema VALUES (1);
CREATE TABLE administrator (
    slot INTEGER PRIMARY KEY CHECK(slot = 1),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE app_user (
    id TEXT PRIMARY KEY,
    label TEXT NOT NULL CHECK(length(label) BETWEEN 1 AND 100),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
    auth_epoch INTEGER NOT NULL DEFAULT 1 CHECK(auth_epoch > 0),
    created_at INTEGER NOT NULL
);
CREATE TABLE binding (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_user(id),
    version INTEGER NOT NULL CHECK(version > 0),
    identity_namespace TEXT NOT NULL,
    account_id TEXT NOT NULL,
    bot_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    origin TEXT NOT NULL,
    evidence_revision TEXT NOT NULL,
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, version),
    UNIQUE(id, user_id),
    UNIQUE(id, user_id, bot_id, sender_id)
);
CREATE UNIQUE INDEX active_user_binding ON binding(user_id) WHERE active = 1;
CREATE UNIQUE INDEX active_bot_binding ON binding(bot_id) WHERE active = 1;
CREATE UNIQUE INDEX active_account_binding ON binding(identity_namespace, account_id) WHERE active = 1;
CREATE TABLE channel_session (
    binding_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    token TEXT NOT NULL CHECK(length(token) > 0),
    scan_user_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK(generation > 0),
    cursor TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(binding_id, user_id) REFERENCES binding(id, user_id)
);
CREATE TABLE binding_attempt (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES app_user(id),
    auth_epoch INTEGER NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('INITIAL','REAUTHENTICATE','REPLACE')),
    phase TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    completed_binding_id TEXT,
    UNIQUE(id, user_id),
    FOREIGN KEY(completed_binding_id, user_id) REFERENCES binding(id, user_id)
);
CREATE UNIQUE INDEX one_pending_attempt ON binding_attempt(user_id)
    WHERE phase IN ('REQUESTING_QR','QR_READY','SCANNED','NEED_PAIRING','VERIFYING_IDENTITY');
CREATE TABLE conversation (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    binding_id TEXT NOT NULL,
    current INTEGER NOT NULL CHECK(current IN (0,1)),
    created_at INTEGER NOT NULL,
    ended_at INTEGER,
    last_user_message_at INTEGER,
    FOREIGN KEY(binding_id, user_id) REFERENCES binding(id, user_id),
    UNIQUE(id, user_id, binding_id)
);
CREATE UNIQUE INDEX current_conversation ON conversation(user_id, binding_id) WHERE current = 1;
CREATE TABLE inbound_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    binding_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    bot_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    auth_epoch INTEGER NOT NULL,
    message_id TEXT NOT NULL CHECK(length(message_id) > 0),
    received_at INTEGER NOT NULL,
    text TEXT,
    context_token TEXT NOT NULL CHECK(length(context_token) > 0),
    kind TEXT NOT NULL CHECK(kind IN ('CHAT','NEW','HELP','NOTICE')),
    FOREIGN KEY(binding_id, user_id, bot_id, sender_id) REFERENCES binding(id, user_id, bot_id, sender_id),
    FOREIGN KEY(conversation_id, user_id, binding_id) REFERENCES conversation(id, user_id, binding_id),
    UNIQUE(bot_id, message_id),
    UNIQUE(sequence, user_id, binding_id, conversation_id)
);
CREATE TABLE turn (
    event_sequence INTEGER PRIMARY KEY,
    user_id TEXT NOT NULL,
    binding_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    stage TEXT NOT NULL CHECK(stage IN ('RECEIVED','PROCESSING','RESPONSE_READY','SENDING','SENT','FAILED','INTERRUPTED','SEND_FAILED','DELIVERY_UNKNOWN')),
    reply_text TEXT,
    model_succeeded INTEGER NOT NULL DEFAULT 0 CHECK(model_succeeded IN (0,1)),
    client_id TEXT UNIQUE,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY(event_sequence, user_id, binding_id, conversation_id)
        REFERENCES inbound_event(sequence, user_id, binding_id, conversation_id)
);
CREATE INDEX pending_user_turns ON turn(user_id, stage, event_sequence);
CREATE TABLE model_configuration (
    version INTEGER PRIMARY KEY AUTOINCREMENT,
    configuration_json TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE admin_setting (
    slot INTEGER PRIMARY KEY CHECK(slot = 1),
    idle_minutes INTEGER NOT NULL DEFAULT 30 CHECK(idle_minutes BETWEEN 1 AND 1440),
    revision INTEGER NOT NULL DEFAULT 1,
    model_version INTEGER REFERENCES model_configuration(version)
);
INSERT INTO admin_setting(slot) VALUES (1);
CREATE TABLE model_attempt (
    id TEXT PRIMARY KEY,
    event_sequence INTEGER NOT NULL,
    user_id TEXT NOT NULL,
    binding_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    model_version INTEGER NOT NULL REFERENCES model_configuration(version),
    attempt_number INTEGER NOT NULL CHECK(attempt_number > 0),
    started_at INTEGER NOT NULL,
    outcome TEXT NOT NULL CHECK(outcome IN ('STARTED','SUCCEEDED','FAILED','UNKNOWN','CANCELLED')),
    FOREIGN KEY(event_sequence, user_id, binding_id, conversation_id)
        REFERENCES inbound_event(sequence, user_id, binding_id, conversation_id),
    UNIQUE(event_sequence, attempt_number)
);
CREATE TABLE model_usage (
    attempt_id TEXT PRIMARY KEY REFERENCES model_attempt(id),
    input_tokens INTEGER CHECK(input_tokens >= 0),
    output_tokens INTEGER CHECK(output_tokens >= 0),
    cached_input_tokens INTEGER CHECK(cached_input_tokens >= 0),
    status TEXT NOT NULL CHECK(status IN ('REPORTED','PARTIAL','UNKNOWN','INVALID')),
    CHECK(input_tokens IS NULL OR cached_input_tokens IS NULL OR cached_input_tokens <= input_tokens)
);
CREATE INDEX attempt_reporting ON model_attempt(user_id, conversation_id, started_at);
CREATE TABLE audit_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT NOT NULL,
    result TEXT NOT NULL,
    occurred_at INTEGER NOT NULL
);
PRAGMA user_version = 1;