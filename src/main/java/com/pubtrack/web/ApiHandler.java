package com.pubtrack.web;

import com.pubtrack.db.DataAccessException;
import com.pubtrack.exception.AppException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for every /api request: parses it, dispatches to the matching route,
 * and turns the result (or exception) into a JSON response with the right status code.
 */
public final class ApiHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final Router router;

    public ApiHandler(Router router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            Optional<Router.Match> match = router.find(method, path);
            if (match.isEmpty()) {
                int status = router.pathExists(path) ? 405 : 404;
                send(exchange, status, error(status == 405 ? "Method not allowed" : "Not found"));
                return;
            }
            String body = readBody(exchange);
            if (body == null) {
                send(exchange, 413, error("Request body too large"));
                return;
            }
            Request request = new Request(match.get().pathParams(), exchange.getRequestURI().getRawQuery(), body,
                    exchange.getRequestHeaders().getFirst(Request.USER_HEADER));
            Object result = match.get().route().handler().handle(request);
            send(exchange, match.get().route().successStatus(), result == null ? Map.of("ok", true) : result);
        } catch (AppException e) {
            send(exchange, e.httpStatus(), error(e.getMessage()));
        } catch (DataAccessException e) {
            if (e.isConstraintViolation()) {
                LOG.log(Level.WARNING, "Constraint violation on " + method + " " + path + ": " + e.getMessage());
                send(exchange, 409, error("That change conflicts with existing data"));
            } else {
                LOG.log(Level.SEVERE, "Database failure on " + method + " " + path, e);
                send(exchange, 500, error("Database error. Please try again."));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unexpected failure on " + method + " " + path, e);
            send(exchange, 500, error("Something went wrong on the server"));
        } finally {
            exchange.close();
        }
    }

    /** Reads the body, or returns null if it is larger than the limit. */
    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            return bytes.length > MAX_BODY_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    private static void send(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
