package io.github.wochen5770.talkweave.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/** Some client libraries log prompts and raw responses even at WARN. Use our typed diagnostics instead. */
public final class SensitiveLibraryLogFilter extends TurboFilter {
    private static final String[] PREFIXES = {"org.springframework.ai", "org.springframework.web.client",
            "org.springframework.web.reactive.function.client", "reactor.netty.http.client",
            "com.zaxxer.hikari", "com.mysql", "io.lettuce.core"};
    @Override public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable error) {
        for (String prefix : PREFIXES) {
            if (logger.getName().equals(prefix) || logger.getName().startsWith(prefix + ".")) return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
