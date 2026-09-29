package io.github.wochen5770.talkweave.managed.persistence;

/** Deliberately does not retain driver messages, SQL values, paths or credentials. */
public final class ManagedProblem extends RuntimeException {
    public enum Code { INCOMPATIBLE_LAYOUT, INVALID_DIRECTORY, DIRECTORY_IN_USE, DATABASE_UNAVAILABLE,
        CONFLICT, NOT_FOUND, UNAUTHORIZED, INVALID_INPUT, EXPIRED, BACKLOG }
    private final Code code;
    public ManagedProblem(Code code) { super("Managed operation failed: " + code); this.code = code; }
    public Code code() { return code; }
}