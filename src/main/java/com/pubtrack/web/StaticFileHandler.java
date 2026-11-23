package com.pubtrack.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Serves the frontend (HTML/CSS/JS) from the classpath folder /static. */
public final class StaticFileHandler implements HttpHandler {

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon");

    private final String root;

    public StaticFileHandler(String root) {
        this.root = root;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            // Reject anything that tries to climb out of the static folder.
            if (path.contains("..") || path.contains("\\")) {
                sendText(exchange, 400, "Bad path");
                return;
            }
            try (InputStream in = StaticFileHandler.class.getResourceAsStream(root + path)) {
                if (in == null) {
                    sendText(exchange, 404, "Not found");
                    return;
                }
                byte[] bytes = in.readAllBytes();
                String ext = path.substring(path.lastIndexOf('.') + 1);
                exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                boolean head = "HEAD".equals(exchange.getRequestMethod());
                exchange.sendResponseHeaders(200, head ? -1 : bytes.length);
                if (!head) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
