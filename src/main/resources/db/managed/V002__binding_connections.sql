CREATE TABLE binding_connection (
    binding_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    bot_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    PRIMARY KEY(binding_id, user_id, bot_id, sender_id),
    FOREIGN KEY(binding_id, user_id) REFERENCES binding(id, user_id)
);
INSERT INTO binding_connection SELECT id, user_id, bot_id, sender_id FROM binding;
CREATE TABLE inbound_event_new (
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
    FOREIGN KEY(binding_id, user_id, bot_id, sender_id)
        REFERENCES binding_connection(binding_id, user_id, bot_id, sender_id),
    FOREIGN KEY(conversation_id, user_id, binding_id) REFERENCES conversation(id, user_id, binding_id),
    UNIQUE(bot_id, message_id),
    UNIQUE(sequence, user_id, binding_id, conversation_id)
);
INSERT INTO inbound_event_new SELECT * FROM inbound_event;
DROP TABLE inbound_event;
ALTER TABLE inbound_event_new RENAME TO inbound_event;
DROP TABLE managed_schema;
CREATE TABLE managed_schema (version INTEGER PRIMARY KEY CHECK(version = 2));
INSERT INTO managed_schema VALUES (2);
PRAGMA user_version = 2;
