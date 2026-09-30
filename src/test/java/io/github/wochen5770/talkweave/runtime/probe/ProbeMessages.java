package io.github.wochen5770.talkweave.runtime.probe;

import io.github.wochen5770.talkweave.channel.wechat.WechatApiClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared diagnostic nonce validation; no probe depends on another probe's entry point. */
final class ProbeMessages {
    private ProbeMessages() { }
    static boolean matches(String expected, WechatApiClient.Incoming message) {
        return !message.group() && message.messageType() == 1 && message.messageState() == 2
                && message.messageId() != null && !message.messageId().isBlank()
                && message.sender() != null && !message.sender().isBlank()
                && message.contextToken() != null && !message.contextToken().isBlank()
                && message.text() != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                message.text().getBytes(StandardCharsets.UTF_8));
    }
}
