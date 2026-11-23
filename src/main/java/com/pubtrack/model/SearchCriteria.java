package com.pubtrack.model;

/**
 * Filters for the manuscript list. {@code viewer} decides visibility:
 * editors see everything, authors see their own papers, reviewers see papers they were invited to.
 */
public record SearchCriteria(
        User viewer,
        ManuscriptStatus status,
        Integer journalId,
        String text,
        int page,
        int pageSize) {
}
