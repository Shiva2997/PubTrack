package com.pubtrack.model;

import java.time.LocalDateTime;

/**
 * One reviewer invited to review one manuscript in one round.
 * {@code reviewerId} is nullable so the record can be anonymised for authors (blind review).
 */
public record ReviewAssignment(
        int id,
        int manuscriptId,
        String manuscriptTitle,
        Integer reviewerId,
        String reviewerName,
        int round,
        LocalDateTime assignedAt,
        LocalDateTime dueAt,
        LocalDateTime completedAt,
        Recommendation recommendation,
        String comments,
        boolean overdue) {

    public boolean isCompleted() {
        return completedAt != null;
    }

    /** Copy with the reviewer's identity replaced by a neutral label such as "Reviewer 1". */
    public ReviewAssignment anonymised(String label) {
        return new ReviewAssignment(id, manuscriptId, manuscriptTitle, null, label, round,
                assignedAt, dueAt, completedAt, recommendation, comments, overdue);
    }
}
