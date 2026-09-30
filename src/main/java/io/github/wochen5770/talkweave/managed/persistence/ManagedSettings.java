package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.model.ModelConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import static io.github.wochen5770.talkweave.managed.persistence.Sql.*;
import static io.github.wochen5770.talkweave.managed.persistence.ManagedProblem.Code.*;

/** Internal settings store. Secret-bearing records must never be returned as management DTOs. */
public final class ManagedSettings {
    public record Settings(int idleMinutes, long revision, Long modelVersion) { }
    public record Administrator(String username, String passwordHash) {
        @Override public String toString() { return "Administrator[REDACTED]"; }
    }
    public record ModelSnapshot(long version, ModelConfiguration configuration) {
        @Override public String toString() { return "ModelSnapshot[version=" + version + ", configuration=REDACTED]"; }
    }
    private final ManagedStore store;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    public ManagedSettings(ManagedStore store, Clock clock) { this.store = store; this.clock = clock; }

    public boolean initializeAdministrator(String username, String passwordHash) {
        return store.transaction(c -> {
            scalar(c, "SELECT slot FROM admin_setting WHERE slot=1 FOR UPDATE");
            if (scalar(c, "SELECT count(*) FROM administrator") != 0) return false;
            if (username == null || username.isBlank() || username.length() > 64 || username.chars().anyMatch(Character::isISOControl)
                    || passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255) throw new ManagedProblem(INVALID_INPUT);
            update(c, "INSERT INTO administrator(slot,username,password_hash,created_at) VALUES (1,?,?,?)", username, passwordHash, clock.millis());
            audit(c, "ADMIN_INITIALIZED", "administrator", clock.millis());
            return true;
        });
    }
    public Optional<Administrator> administrator() {
        return store.transaction(c -> {
            try (var s = prepare(c, "SELECT username,password_hash FROM administrator WHERE slot=1"); var r = s.executeQuery()) {
                return r.next() ? Optional.of(new Administrator(r.getString(1), r.getString(2))) : Optional.empty();
            }
        });
    }
    public Settings settings() {
        return store.transaction(c -> {
            try (var s = prepare(c, "SELECT idle_minutes,revision,model_version FROM admin_setting WHERE slot=1"); var r = s.executeQuery()) {
                if (!r.next()) throw new ManagedProblem(DATABASE_UNAVAILABLE);
                return new Settings(r.getInt(1), r.getLong(2), nullableLong(r, "model_version"));
            }
        });
    }
    public void setIdleMinutes(int minutes) {
        if (minutes < 1 || minutes > 1440) throw new ManagedProblem(INVALID_INPUT);
        store.transaction(c -> {
            update(c, "UPDATE admin_setting SET idle_minutes=?,revision=revision+1 WHERE slot=1", minutes);
            audit(c, "IDLE_TIMEOUT_UPDATED", "settings", clock.millis()); return null;
        });
    }
    public ModelSnapshot saveModel(ModelConfiguration model) {
        if (model == null) throw new ManagedProblem(INVALID_INPUT);
        model.validate();
        final String serialized;
        try { serialized = json.writeValueAsString(model); }
        catch (Exception ignored) { throw new ManagedProblem(INVALID_INPUT); }
        return store.transaction(c -> {
            scalar(c, "SELECT slot FROM admin_setting WHERE slot=1 FOR UPDATE");
            long version = insertId(c, "INSERT INTO model_configuration(configuration_json,created_at) VALUES (?,?)", serialized, clock.millis());
            update(c, "UPDATE admin_setting SET model_version=?,revision=revision+1 WHERE slot=1", version);
            audit(c, "MODEL_CONFIGURATION_UPDATED", "settings", clock.millis());
            return new ModelSnapshot(version, model);
        });
    }
    public Optional<ModelSnapshot> currentModel() {
        return store.transaction(c -> {
            try (var s = prepare(c, "SELECT m.version,m.configuration_json FROM model_configuration m JOIN admin_setting s ON s.model_version=m.version WHERE s.slot=1"); var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                try { return Optional.of(new ModelSnapshot(r.getLong(1), json.readValue(r.getString(2), ModelConfiguration.class))); }
                catch (Exception ignored) { throw new ManagedProblem(DATABASE_UNAVAILABLE); }
            }
        });
    }
}
