package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.managed.persistence.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Set;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("managed")
public class AdminSecurity {
    @Bean SecurityFilterChain administratorSecurity(HttpSecurity http, AdminIdentity identity, ManagedAudit audit, Clock clock) throws Exception {
        var throttle = new LoginThrottle(clock);
        http.authenticationProvider(identity)
            .authorizeHttpRequests(a -> a.requestMatchers("/", "/index.html", "/app.js", "/app.css", "/api/admin/session", "/api/admin/login").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN").anyRequest().denyAll())
            .requestCache(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, failure) -> json(res, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler((req, res, failure) -> json(res, 403, "FORBIDDEN")))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")))
            .formLogin(f -> f.loginProcessingUrl("/api/admin/login")
                    .successHandler((req, res, auth) -> {
                        try { audit.security(ManagedAudit.SecurityEvent.LOGIN_SUCCEEDED); json(res, 200, "SIGNED_IN"); }
                        catch (ManagedProblem failure) {
                            SecurityContextHolder.clearContext();
                            if (req.getSession(false) != null) req.getSession(false).invalidate();
                            json(res, 503, "UNAVAILABLE");
                        }
                    })
                    .failureHandler((req, res, failure) -> {
                        try { audit.security(ManagedAudit.SecurityEvent.LOGIN_FAILED); json(res, 401, "INVALID_CREDENTIALS"); }
                        catch (ManagedProblem unavailable) { json(res, 503, "UNAVAILABLE"); }
                    }))
            .logout(l -> l.logoutUrl("/api/admin/logout").deleteCookies("TALKWEAVE_ADMIN")
                    .logoutSuccessHandler((req, res, auth) -> {
                        try { audit.security(ManagedAudit.SecurityEvent.LOGOUT); json(res, 200, "SIGNED_OUT"); }
                        catch (ManagedProblem failure) { json(res, 503, "UNAVAILABLE"); }
                    }))
            .addFilterBefore(new SourceGuard(), CsrfFilter.class)
            .addFilterBefore(new LoginGuard(throttle, audit), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static void json(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store"); response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
    /** Global bounded window: do not trust spoofable X-Forwarded-For or allocate one entry per IP. */
    static final class LoginThrottle {
        private final Clock clock;
        private long window;
        private int attempts;
        LoginThrottle(Clock clock) { this.clock = clock; this.window = clock.millis(); }
        synchronized boolean allow() {
            long now = clock.millis();
            if (now - window >= 60_000 || now < window) { window = now; attempts = 0; }
            if (attempts >= 5) return false;
            attempts++; return true;
        }
    }
    static final class LoginGuard extends OncePerRequestFilter {
        private final LoginThrottle throttle;
        private final ManagedAudit audit;
        private boolean recordedThrottle;
        LoginGuard(LoginThrottle throttle, ManagedAudit audit) { this.throttle = throttle; this.audit = audit; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            if (req.getServletPath().equals("/api/admin/login") && req.getMethod().equals("POST")) {
                boolean allowed;
                synchronized (this) {
                    allowed = throttle.allow();
                    if (!allowed && !recordedThrottle) {
                        try { audit.security(ManagedAudit.SecurityEvent.LOGIN_THROTTLED); }
                        catch (ManagedProblem failure) { json(res, 503, "UNAVAILABLE"); return; }
                        recordedThrottle = true;
                    } else if (allowed) recordedThrottle = false;
                }
                if (!allowed) { res.setHeader("Retry-After", "60"); json(res, 429, "LOGIN_THROTTLED"); return; }
            }
            chain.doFilter(req, res);
        }
    }
    static final class SourceGuard extends OncePerRequestFilter {
        private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            res.setHeader("Cache-Control", "no-store");
            boolean unsafe = !SAFE.contains(req.getMethod());
            String origin = req.getHeader("Origin");
            if ((unsafe && origin == null) || (origin != null && !sameOrigin(req, origin))
                    || (unsafe && "cross-site".equals(req.getHeader("Sec-Fetch-Site")))) {
                json(res, 403, "FORBIDDEN"); return;
            }
            if (req.getContentLengthLong() > 65_536) { json(res, 413, "REQUEST_TOO_LARGE"); return; }
            chain.doFilter(new HttpServletRequestWrapper(req) {
                @Override public ServletInputStream getInputStream() throws IOException {
                    ServletInputStream source = super.getInputStream();
                    return new ServletInputStream() {
                        private int bytes;
                        @Override public int read() throws IOException {
                            int b = source.read();
                            if (b != -1 && ++bytes > 65_536) throw new IOException("Request size limit exceeded");
                            return b;
                        }
                        @Override public boolean isFinished() { return source.isFinished(); }
                        @Override public boolean isReady() { return source.isReady(); }
                        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                    };
                }
            }, res);
        }
        private static boolean sameOrigin(HttpServletRequest req, String value) {
            try {
                URI uri = URI.create(value);
                int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
                return uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                        && req.getScheme().equalsIgnoreCase(uri.getScheme()) && req.getServerName().equalsIgnoreCase(uri.getHost()) && req.getServerPort() == port;
            } catch (IllegalArgumentException failure) { return false; }
        }
    }
}
