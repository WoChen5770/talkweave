package io.github.wochen5770.talkweave.managed.config;

import io.github.wochen5770.talkweave.managed.cache.HistoryCacheFrame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCacheFrameTest {
    @Test void fixedWidthLongHeaderIsExactAndLocaleIndependent() {
        assertEquals("00000000000000000000000000000\n{}", HistoryCacheFrame.encode(0, 0, "{}", 32));
        assertTrue(HistoryCacheFrame.encode(Long.MAX_VALUE, 4, "{}", 32).startsWith("92233720368547758070000000004\n"));
        assertTrue(HistoryCacheFrame.encode(9_007_199_254_740_993L, 4, "{}", 32)
                .compareTo(HistoryCacheFrame.encode(9_007_199_254_740_992L, 4, "{}", 32)) > 0);
    }
    @Test void budgetCountsUtf8BytesAndRejectsInvalidVersions() {
        assertThrows(IllegalArgumentException.class, () -> HistoryCacheFrame.encode(1, 1, "🙂", 33));
        assertEquals(32, HistoryCacheFrame.encode(1, 1, "🙂", 34).length());
        assertThrows(IllegalArgumentException.class, () -> HistoryCacheFrame.encode(-1, 1, "{}", 64));
        assertThrows(IllegalArgumentException.class, () -> HistoryCacheFrame.encode(1, -1, "{}", 64));
    }
}
