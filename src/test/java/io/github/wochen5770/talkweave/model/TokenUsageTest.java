package io.github.wochen5770.talkweave.model;

import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TokenUsageTest {
    @Test void cachedIsASubsetAndAbsentIsNotZero() {
        assertThat(TokenUsage.fromCompatibleUsage(Map.of("prompt_tokens", 2000, "completion_tokens", 100,
                "prompt_tokens_details", Map.of("cached_tokens", 1500))))
                .isEqualTo(new TokenUsage(2000L, 100L, 1500L, TokenUsage.Status.REPORTED));
        assertThat(TokenUsage.fromCompatibleUsage(Map.of("prompt_tokens", 2000, "completion_tokens", 100)))
                .isEqualTo(new TokenUsage(2000L, 100L, null, TokenUsage.Status.PARTIAL));
        assertThat(TokenUsage.fromCompatibleUsage(Map.of())).isEqualTo(TokenUsage.unknown());
    }
    @Test void zeroMustBeReportedExplicitlyAndVendorKeysAreNotGuessed() {
        assertThat(TokenUsage.normalize(0, 0, 0).status()).isEqualTo(TokenUsage.Status.REPORTED);
        assertThat(TokenUsage.fromCompatibleUsage(Map.of("cache_read_input_tokens", 2000))).isEqualTo(TokenUsage.unknown());
    }
    @Test void invalidCountsDoNotThrowAwayValidCountsOrBreakAnAnswer() {
        assertThat(TokenUsage.normalize(100, 10, 101))
                .isEqualTo(new TokenUsage(100L, 10L, null, TokenUsage.Status.INVALID));
        for (Object invalid : new Object[]{-1, "500", 1.5, Double.NaN, BigInteger.ONE.shiftLeft(100)}) {
            assertThat(TokenUsage.normalize(invalid, 10, null))
                    .isEqualTo(new TokenUsage(null, 10L, null, TokenUsage.Status.INVALID));
        }
    }
}