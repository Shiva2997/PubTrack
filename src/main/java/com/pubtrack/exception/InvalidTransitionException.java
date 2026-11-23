package com.pubtrack.exception;

import com.pubtrack.model.ManuscriptStatus;

/** A workflow action is not allowed from the manuscript's current status (HTTP 409). */
public class InvalidTransitionException extends ConflictException {

    public InvalidTransitionException(ManuscriptStatus from, ManuscriptStatus to) {
        super("A manuscript cannot move from " + from + " to " + to);
    }

    public InvalidTransitionException(String message) {
        super(message);
    }
}
