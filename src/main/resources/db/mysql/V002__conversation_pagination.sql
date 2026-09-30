ALTER TABLE conversation
    ADD COLUMN sequence BIGINT NOT NULL AUTO_INCREMENT,
    ADD UNIQUE KEY conversation_sequence (sequence),
    ADD KEY user_conversations (user_id, sequence);
