package com.pubtrack.model;

import java.util.List;

/**
 * Everything the detail screen needs. {@code actions} lists what the current user may do
 * right now (computed on the server so the UI never has to guess the business rules).
 */
public record ManuscriptDetail(
        Manuscript manuscript,
        List<ReviewAssignment> reviews,
        List<StatusEvent> history,
        List<String> actions) {
}
