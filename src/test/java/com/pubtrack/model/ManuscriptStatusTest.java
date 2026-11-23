package com.pubtrack.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManuscriptStatusTest {

    @Test
    void submittedCanGoToReviewRejectOrWithdraw() {
        assertTrue(ManuscriptStatus.SUBMITTED.canMoveTo(ManuscriptStatus.UNDER_REVIEW));
        assertTrue(ManuscriptStatus.SUBMITTED.canMoveTo(ManuscriptStatus.REJECTED));
        assertTrue(ManuscriptStatus.SUBMITTED.canMoveTo(ManuscriptStatus.WITHDRAWN));
        assertFalse(ManuscriptStatus.SUBMITTED.canMoveTo(ManuscriptStatus.ACCEPTED));
    }

    @Test
    void revisionGoesBackToSubmitted() {
        assertTrue(ManuscriptStatus.REVISION_REQUESTED.canMoveTo(ManuscriptStatus.SUBMITTED));
        assertFalse(ManuscriptStatus.REVISION_REQUESTED.canMoveTo(ManuscriptStatus.ACCEPTED));
    }

    @ParameterizedTest
    @EnumSource(value = ManuscriptStatus.class, names = {"ACCEPTED", "REJECTED", "WITHDRAWN"})
    void finalStatesAreTerminal(ManuscriptStatus status) {
        assertTrue(status.isTerminal());
        for (ManuscriptStatus target : ManuscriptStatus.values()) {
            assertFalse(status.canMoveTo(target), status + " must not move to " + target);
        }
    }

    @Test
    void noStatusCanMoveToItself() {
        for (ManuscriptStatus s : ManuscriptStatus.values()) {
            assertFalse(s.canMoveTo(s));
        }
    }

    @Test
    void allowedNextIsReadOnly() {
        assertThrows(UnsupportedOperationException.class,
                () -> ManuscriptStatus.SUBMITTED.allowedNext().add(ManuscriptStatus.ACCEPTED));
    }

    @Test
    void decisionsMapToStatuses() {
        assertTrue(Decision.ACCEPT.resultingStatus().isDecision());
        assertTrue(Decision.REJECT.resultingStatus().isDecision());
        assertFalse(Decision.REVISE.resultingStatus().isDecision());
    }
}
