package com.pubtrack.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a manuscript, modelled as a finite state machine.
 *
 * <pre>
 *   SUBMITTED ──► UNDER_REVIEW ──► ACCEPTED
 *      │  ▲            │   └──────► REJECTED
 *      │  │            ▼
 *      │  └── REVISION_REQUESTED
 *      ├──► REJECTED (desk reject)
 *      └──► WITHDRAWN (author, from any non-final state)
 * </pre>
 *
 * Keeping the allowed transitions inside the enum means no other class can
 * move a manuscript into an illegal state by accident.
 */
public enum ManuscriptStatus {
    SUBMITTED,
    UNDER_REVIEW,
    REVISION_REQUESTED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN;

    /** States this status may legally move to next. */
    public Set<ManuscriptStatus> allowedNext() {
        Set<ManuscriptStatus> next = switch (this) {
            case SUBMITTED -> EnumSet.of(UNDER_REVIEW, REJECTED, WITHDRAWN);
            case UNDER_REVIEW -> EnumSet.of(REVISION_REQUESTED, ACCEPTED, REJECTED, WITHDRAWN);
            case REVISION_REQUESTED -> EnumSet.of(SUBMITTED, WITHDRAWN);
            case ACCEPTED, REJECTED, WITHDRAWN -> EnumSet.noneOf(ManuscriptStatus.class);
        };
        return Collections.unmodifiableSet(next);
    }

    public boolean canMoveTo(ManuscriptStatus target) {
        return allowedNext().contains(target);
    }

    /** A final decision has been made (or the author withdrew); nothing else can happen. */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** States that count as a final editorial decision (sets decided_at). */
    public boolean isDecision() {
        return this == ACCEPTED || this == REJECTED;
    }
}
