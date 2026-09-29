package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.managed.persistence.ManagedSettings;
import io.github.wochen5770.talkweave.runtime.ConfigurationProblem;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;

/** One persisted administrator; bootstrap values never reset an existing account. */
public final class AdminIdentity implements AuthenticationProvider {
    private final ManagedSettings settings;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public AdminIdentity(ManagedSettings settings, PasswordEncoder passwords, String username, String initialPassword) {
        this.settings = settings;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
        if (settings.administrator().isEmpty() && (initialPassword == null || initialPassword.isEmpty()))
            throw new ConfigurationProblem("managed.bootstrap-password", "set ADMIN_INIT_PASSWORD for first administrator initialization");
        if (settings.administrator().isEmpty() && initialPassword != null && !initialPassword.isEmpty()) {
            int bytes = initialPassword.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 12 || bytes > 72 || initialPassword.chars().anyMatch(Character::isISOControl))
                throw new ConfigurationProblem("managed.bootstrap-password", "must contain 12–72 UTF-8 bytes and no control characters");
            settings.initializeAdministrator(username, passwords.encode(initialPassword));
        }
    }
    @Override public Authentication authenticate(Authentication request) {
        var account = settings.administrator();
        String supplied = request.getCredentials() instanceof String s ? s : "";
        boolean lengthValid = supplied.getBytes(StandardCharsets.UTF_8).length <= 72;
        // Perform the same expensive check even for an unknown username or missing bootstrap.
        String hash = account.map(ManagedSettings.Administrator::passwordHash).orElse(dummyHash);
        boolean matches = passwords.matches(lengthValid ? supplied : "", hash);
        if (!lengthValid || !matches || account.isEmpty() || !account.get().username().equals(request.getName()))
            throw new BadCredentialsException("Invalid administrator credentials");
        return UsernamePasswordAuthenticationToken.authenticated(account.get().username(), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
    @Override public boolean supports(Class<?> type) { return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type); }
    @Override public String toString() { return "AdminIdentity[REDACTED]"; }
}
