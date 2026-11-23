package com.pubtrack.web;

import com.pubtrack.exception.UnauthorizedException;
import com.pubtrack.exception.ValidationException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** An incoming API request with path parameters, query string and form body already parsed. */
public final class Request {

    public static final String USER_HEADER = "X-User-Id";

    private final List<String> pathParams;
    private final Map<String, String> query;
    private final Map<String, String> form;
    private final String userHeader;

    public Request(List<String> pathParams, String rawQuery, String formBody, String userHeader) {
        this.pathParams = pathParams;
        this.query = parseUrlEncoded(rawQuery);
        this.form = parseUrlEncoded(formBody);
        this.userHeader = userHeader;
    }

    /** Numeric path parameter, e.g. the 42 in /api/manuscripts/42. */
    public int pathInt(int index) {
        return parseInt("id", pathParams.get(index));
    }

    public String query(String name) {
        return blankToNull(query.get(name));
    }

    public Integer queryInt(String name) {
        String v = query(name);
        return v == null ? null : parseInt(name, v);
    }

    public String form(String name) {
        return form.get(name);
    }

    public int formInt(String name) {
        String v = blankToNull(form.get(name));
        if (v == null) {
            throw new ValidationException(name + " is required");
        }
        return parseInt(name, v);
    }

    /**
     * The acting user. This demo identifies users with a header chosen in the UI;
     * a production system would use a session or a signed token instead.
     */
    public int actingUserId() {
        if (userHeader == null || userHeader.isBlank()) {
            throw new UnauthorizedException("Choose a user first (missing " + USER_HEADER + " header)");
        }
        try {
            return Integer.parseInt(userHeader.trim());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("Invalid " + USER_HEADER + " header");
        }
    }

    static Map<String, String> parseUrlEncoded(String s) {
        if (s == null || s.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Malformed request parameters");
            }
        }
        return result;
    }

    private static int parseInt(String name, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException(name + " must be a whole number");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
