package com.pubtrack.web;

import com.pubtrack.exception.UnauthorizedException;
import com.pubtrack.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestTest {

    @Test
    void parsesFormQueryAndPathParameters() {
        Request r = new Request(List.of("42"), "q=graph+neural&page=2&status=", "title=A%20%26%20B&journalId=3", "5");
        assertEquals(42, r.pathInt(0));
        assertEquals("graph neural", r.query("q"));
        assertEquals(2, r.queryInt("page"));
        assertNull(r.query("status"));
        assertEquals("A & B", r.form("title"));
        assertEquals(3, r.formInt("journalId"));
        assertEquals(5, r.actingUserId());
    }

    @Test
    void rejectsBadNumbersAndMissingUser() {
        Request r = new Request(List.of(), "page=abc", "", null);
        assertThrows(ValidationException.class, () -> r.queryInt("page"));
        assertThrows(ValidationException.class, () -> r.formInt("journalId"));
        assertThrows(UnauthorizedException.class, r::actingUserId);
    }

    @Test
    void routerMatchesMethodAndPath() {
        Router router = new Router().get("/api/manuscripts/(\\d+)", req -> "ok");
        assertEquals(List.of("12"), router.find("GET", "/api/manuscripts/12").orElseThrow().pathParams());
        assertEquals(true, router.find("POST", "/api/manuscripts/12").isEmpty());
        assertEquals(true, router.pathExists("/api/manuscripts/12"));
        assertEquals(false, router.pathExists("/api/manuscripts/abc"));
    }
}
