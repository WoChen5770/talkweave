package io.github.personalassistant.persistence;

/** Deliberately does not expose driver exceptions, SQL or filesystem paths. */
public final class StorageProblem extends IllegalStateException {
    public enum Reason { INVALID_DIRECTORY, DIRECTORY_IN_USE, DATABASE_UNAVAILABLE, UNSUPPORTED_SCHEMA, STALE_CHANNEL, UNAUTHORIZED, INVALID_TRANSITION, BACKLOG, LOW_DISK }
    private final Reason reason;
    public StorageProblem(Reason reason) {
        super("Persistent storage unavailable: " + reason);
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
