package com.pubtrack.model;

/** An editor's decision, and the manuscript status each one leads to. */
public enum Decision {
    ACCEPT(ManuscriptStatus.ACCEPTED),
    REVISE(ManuscriptStatus.REVISION_REQUESTED),
    REJECT(ManuscriptStatus.REJECTED);

    private final ManuscriptStatus resultingStatus;

    Decision(ManuscriptStatus resultingStatus) {
        this.resultingStatus = resultingStatus;
    }

    public ManuscriptStatus resultingStatus() {
        return resultingStatus;
    }
}
