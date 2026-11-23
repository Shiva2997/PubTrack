package com.pubtrack.model;

import java.time.LocalDateTime;

/**
 * A manuscript as shown to users, already joined with journal and author names.
 * {@code reviewsDone}/{@code reviewsTotal} count reviews in the current round only.
 */
public record Manuscript(
        int id,
        int journalId,
        String journalName,
        int authorId,
        String authorName,
        String title,
        String abstractText,
        String keywords,
        ManuscriptStatus status,
        int currentRound,
        LocalDateTime submittedAt,
        LocalDateTime updatedAt,
        LocalDateTime decidedAt,
        int reviewsDone,
        int reviewsTotal) {
}
