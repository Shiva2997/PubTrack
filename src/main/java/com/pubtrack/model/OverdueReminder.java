package com.pubtrack.model;

import java.time.LocalDateTime;

/** Data needed to send one overdue-review reminder. */
public record OverdueReminder(
        int assignmentId,
        String reviewerName,
        String reviewerEmail,
        String manuscriptTitle,
        LocalDateTime dueAt) {
}
