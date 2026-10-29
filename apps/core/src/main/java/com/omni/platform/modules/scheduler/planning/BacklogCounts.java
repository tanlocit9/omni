package com.omni.platform.modules.scheduler.planning;

/** Mutually exclusive scheduler-outbox counts for one dispatch scope. */
public record BacklogCounts(long pending, long dispatchable, long retryDelayed, long inFlight) {

    public BacklogCounts {
        if (pending < 0 || dispatchable < 0 || retryDelayed < 0 || inFlight < 0) {
            throw new IllegalArgumentException("Backlog counts must not be negative");
        }
        if (pending != dispatchable + retryDelayed + inFlight) {
            throw new IllegalArgumentException(
                    "Pending count must equal dispatchable + retryDelayed + inFlight");
        }
    }

    public boolean occupied() {
        return pending > 0;
    }

    public boolean hasDispatchableWork() {
        return dispatchable > 0;
    }
}
