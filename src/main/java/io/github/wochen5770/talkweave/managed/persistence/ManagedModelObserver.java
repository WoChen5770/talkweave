package io.github.wochen5770.talkweave.managed.persistence;

import io.github.wochen5770.talkweave.model.*;
import java.util.Objects;

/** One instance per logical model call. Callback failures propagate; neither HTTP nor model is replayed. */
public final class ManagedModelObserver implements ModelAttemptObserver {
    private final ManagedUsage usage;
    private final ManagedScope scope;
    private final ModelRequestContext context;
    private String attemptId;
    private int expectedNumber = 1;
    public ManagedModelObserver(ManagedUsage usage, ManagedScope scope, ModelRequestContext context) {
        this.usage = Objects.requireNonNull(usage);
        this.scope = Objects.requireNonNull(scope);
        this.context = Objects.requireNonNull(context);
    }
    @Override public void beforeAttempt(int number) {
        if (number != expectedNumber || attemptId != null) throw new ManagedProblem(ManagedProblem.Code.CONFLICT);
        // Each retry independently verifies the current generation/epoch, event and conversation scope.
        attemptId = usage.begin(scope, context);
    }
    @Override public void afterAttempt(int number, Outcome outcome, TokenUsage tokens) {
        if (number != expectedNumber || attemptId == null) throw new ManagedProblem(ManagedProblem.Code.CONFLICT);
        usage.record(attemptId, ManagedUsage.Outcome.valueOf(outcome.name()), tokens);
        attemptId = null; expectedNumber++;
    }
    @Override public String toString() { return "ManagedModelObserver[REDACTED]"; }
}
