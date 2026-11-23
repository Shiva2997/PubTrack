package com.pubtrack.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps (HTTP method, path regex) pairs to handlers. Capture groups become path parameters. */
public final class Router {

    @FunctionalInterface
    public interface Handler {
        Object handle(Request request);
    }

    record Route(String method, Pattern pattern, Handler handler, int successStatus) {
    }

    /** Result of looking up a path: the route plus its captured path parameters. */
    record Match(Route route, List<String> pathParams) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String regex, Handler handler) {
        return add("GET", regex, handler, 200);
    }

    public Router post(String regex, Handler handler) {
        return add("POST", regex, handler, 200);
    }

    /** POST that creates something and answers 201 Created. */
    public Router create(String regex, Handler handler) {
        return add("POST", regex, handler, 201);
    }

    private Router add(String method, String regex, Handler handler, int status) {
        routes.add(new Route(method, Pattern.compile("^" + regex + "$"), handler, status));
        return this;
    }

    /** Empty if no route matches the path; {@link #pathExists} tells 404 apart from 405. */
    Optional<Match> find(String method, String path) {
        for (Route r : routes) {
            Matcher m = r.pattern().matcher(path);
            if (r.method().equals(method) && m.matches()) {
                List<String> params = new ArrayList<>();
                for (int i = 1; i <= m.groupCount(); i++) {
                    params.add(m.group(i));
                }
                return Optional.of(new Match(r, params));
            }
        }
        return Optional.empty();
    }

    boolean pathExists(String path) {
        return routes.stream().anyMatch(r -> r.pattern().matcher(path).matches());
    }
}
