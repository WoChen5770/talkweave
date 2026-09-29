package io.github.personalassistant.channel.wechat;

import io.github.personalassistant.persistence.ConversationRepository;
import io.github.personalassistant.runtime.PrivateStateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Out-of-band local challenge yields a candidate only; binding still requires an explicit config edit and restart. */
public final class IdentityVerifier {
    private final ConversationRepository repository;
    private final PrivateStateFiles files;
    private long generation = -1;
    private String challenge;
    private boolean matched;
    public IdentityVerifier(ConversationRepository repository, PrivateStateFiles files) throws IOException {
        this.repository = repository; this.files = files; files.remove("identity.json");
    }
    public synchronized void prepare(ConversationRepository.Session session) throws IOException {
        if (repository.isBound(session)) {
            if (generation != session.generation() || challenge != null) files.remove("identity.json");
            generation = session.generation(); challenge = null;
            return;
        }
        if (generation == session.generation()) return;
        String nextChallenge = "bind-" + UUID.randomUUID();
        files.writeJson("identity.json", Map.of("phase", "SEND_CHALLENGE", "expectedMessage", nextChallenge,
                "generation", session.generation(), "instructions", "Send this text in the bot chat, then review the candidate and edit bot-id/owner-id locally. No automatic binding."));
        generation = session.generation(); matched = false; challenge = nextChallenge;
    }
    public synchronized void observe(ConversationRepository.Session session, List<ConversationRepository.Inbound> messages) throws IOException {
        if (generation != session.generation() || repository.isBound(session) || matched || challenge == null) return;
        var current = repository.session();
        if (current.isEmpty() || !current.get().active() || current.get().generation() != session.generation()) return;
        for (var message : messages) {
            if (message.group() || !message.userMessage() || !message.complete() || !message.supportedText()
                    || blank(message.messageId()) || blank(message.senderId()) || blank(message.contextToken()) || message.text() == null) continue;
            if (!MessageDigest.isEqual(challenge.getBytes(StandardCharsets.UTF_8), message.text().getBytes(StandardCharsets.UTF_8))) continue;
            files.writeJson("identity.json", Map.of("phase", "CANDIDATE_REQUIRES_CONFIRMATION", "botId", session.botId(),
                    "ownerId", message.senderId(), "scanUserId", session.scanUserId(), "generation", generation,
                    "verifiedAt", Instant.now().toString(), "nonceMatched", true));
            matched = true;
            return;
        }
    }
    public synchronized void clear() throws IOException {
        if (generation != -1) files.remove("identity.json");
        generation = -1; challenge = null; matched = false;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}