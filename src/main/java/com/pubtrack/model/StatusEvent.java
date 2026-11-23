package com.pubtrack.model;

import java.time.LocalDateTime;

/** One row of a manuscript's audit trail. {@code fromStatus} is null for the first submission. */
public record StatusEvent(
        ManuscriptStatus fromStatus,
        ManuscriptStatus toStatus,
        String changedBy,
        LocalDateTime changedAt,
        String note) {
}
