package com.pubtrack.model;

/** A journal; {@code reviewDays} is how long reviewers get before a review is overdue. */
public record Journal(int id, String name, String subjectArea, int reviewDays) {
}
