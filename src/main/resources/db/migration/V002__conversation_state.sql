CREATE TABLE channel_state (
    slot INTEGER PRIMARY KEY CHECK (slot = 1),
    bot_id TEXT NOT NULL CHECK (length(bot_id) > 0),
    origin TEXT NOT NULL,
    token TEXT NOT NULL CHECK (length(token) > 0),
    scan_user_id TEXT NOT NULL,
    generation INTEGER NOT NULL CHECK (generation > 0),
    cursor TEXT NOT NULL DEFAULT '',
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    updated_at TEXT NOT NULL
);
CREATE TABLE inbound_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_id TEXT NOT NULL,
    message_id TEXT,
    sender_id TEXT,
    generation INTEGER NOT NULL CHECK (generation > 0),
    text TEXT,
    context_token TEXT,
    content_kind TEXT NOT NULL CHECK (content_kind IN ('TEXT', 'UNSUPPORTED')),
    disposition TEXT NOT NULL CHECK (disposition IN ('ACCEPTED', 'IGNORED', 'QUARANTINED')),
    diagnostic TEXT,
    received_at TEXT NOT NULL,
    UNIQUE (bot_id, message_id),
    UNIQUE (sequence, bot_id, sender_id),
    CHECK (message_id IS NULL OR length(message_id) > 0),
    CHECK (disposition != 'ACCEPTED' OR (message_id IS NOT NULL AND sender_id IS NOT NULL AND length(sender_id) > 0 AND context_token IS NOT NULL AND length(context_token) > 0)),
    CHECK (disposition = 'ACCEPTED' OR (text IS NULL AND context_token IS NULL))
);
CREATE TABLE conversation (
    id TEXT PRIMARY KEY,
    bot_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    is_current INTEGER NOT NULL CHECK (is_current IN (0, 1)),
    created_at TEXT NOT NULL,
    UNIQUE (id, bot_id, owner_id)
);
CREATE UNIQUE INDEX one_current_conversation ON conversation(bot_id, owner_id) WHERE is_current = 1;
CREATE TABLE turn (
    event_sequence INTEGER PRIMARY KEY,
    bot_id TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    conversation_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('CHAT', 'COMMAND', 'NOTICE')),
    stage TEXT NOT NULL CHECK (stage IN ('RECEIVED', 'PROCESSING', 'RESPONSE_READY', 'SENDING', 'SENT', 'FAILED', 'INTERRUPTED', 'SEND_FAILED', 'DELIVERY_UNKNOWN')),
    model_status TEXT NOT NULL CHECK (model_status IN ('NONE', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    delivery_status TEXT NOT NULL CHECK (delivery_status IN ('NONE', 'READY', 'SENDING', 'CONFIRMED', 'FAILED', 'UNKNOWN')),
    full_result TEXT,
    reply_text TEXT,
    client_id TEXT UNIQUE,
    diagnostic TEXT,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (event_sequence, bot_id, owner_id) REFERENCES inbound_event(sequence, bot_id, sender_id),
    FOREIGN KEY (conversation_id, bot_id, owner_id) REFERENCES conversation(id, bot_id, owner_id),
    CHECK (stage = 'RECEIVED' OR conversation_id IS NOT NULL OR stage = 'INTERRUPTED'),
    CHECK (stage NOT IN ('RESPONSE_READY', 'SENDING', 'SENT', 'SEND_FAILED', 'DELIVERY_UNKNOWN') OR (reply_text IS NOT NULL AND length(reply_text) > 0 AND client_id IS NOT NULL AND length(client_id) > 0)),
    CHECK (model_status != 'SUCCEEDED' OR (kind = 'CHAT' AND full_result IS NOT NULL AND length(full_result) > 0)),
    CHECK (kind = 'CHAT' OR model_status = 'NONE'),
    CHECK (stage != 'RECEIVED' OR (model_status = 'NONE' AND delivery_status = 'NONE')),
    CHECK (stage != 'PROCESSING' OR delivery_status = 'NONE'),
    CHECK (stage != 'RESPONSE_READY' OR delivery_status = 'READY'),
    CHECK (stage != 'SENDING' OR delivery_status = 'SENDING'),
    CHECK (stage != 'SENT' OR delivery_status = 'CONFIRMED'),
    CHECK (stage != 'SEND_FAILED' OR delivery_status = 'FAILED'),
    CHECK (stage != 'DELIVERY_UNKNOWN' OR delivery_status = 'UNKNOWN')
);
CREATE INDEX pending_turns ON turn(stage, event_sequence);
CREATE INDEX history_turns ON turn(conversation_id, event_sequence);