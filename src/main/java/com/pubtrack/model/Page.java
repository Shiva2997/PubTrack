package com.pubtrack.model;

import java.util.List;

/** One page of results plus the information the UI needs to draw a pager. */
public record Page<T>(List<T> items, int page, int pageSize, long total) {

    public int totalPages() {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }
}
