package com.pubtrack.web;

import com.pubtrack.exception.ValidationException;
import com.pubtrack.model.ManuscriptStatus;
import com.pubtrack.service.ManuscriptService;
import com.pubtrack.service.StatsService;

import java.util.Locale;

/** The REST API: one line per endpoint, each delegating straight to a service method. */
public final class ApiRoutes {

    private ApiRoutes() {
    }

    public static Router build(ManuscriptService manuscripts, StatsService stats) {
        return new Router()
                .get("/api/users", req -> manuscripts.listUsers())
                .get("/api/journals", req -> manuscripts.listJournals())

                .get("/api/manuscripts", req -> manuscripts.search(
                        req.actingUserId(),
                        parseStatus(req.query("status")),
                        req.queryInt("journalId"),
                        req.query("q"),
                        req.queryInt("page") == null ? 1 : req.queryInt("page")))
                .create("/api/manuscripts", req -> manuscripts.submit(
                        req.actingUserId(),
                        req.formInt("journalId"),
                        req.form("title"),
                        req.form("abstract"),
                        req.form("keywords")))
                .get("/api/manuscripts/(\\d+)", req -> manuscripts.getDetail(req.actingUserId(), req.pathInt(0)))

                .post("/api/manuscripts/(\\d+)/reviewers", req -> {
                    manuscripts.assignReviewer(req.actingUserId(), req.pathInt(0), req.formInt("reviewerId"));
                    return null;
                })
                .post("/api/manuscripts/(\\d+)/decision", req -> {
                    manuscripts.decide(req.actingUserId(), req.pathInt(0), req.form("decision"), req.form("note"));
                    return null;
                })
                .post("/api/manuscripts/(\\d+)/resubmit", req -> {
                    manuscripts.resubmit(req.actingUserId(), req.pathInt(0), req.form("note"));
                    return null;
                })
                .post("/api/manuscripts/(\\d+)/withdraw", req -> {
                    manuscripts.withdraw(req.actingUserId(), req.pathInt(0), req.form("note"));
                    return null;
                })

                .get("/api/reviews/mine", req -> manuscripts.myReviews(req.actingUserId()))
                .post("/api/reviews/(\\d+)", req -> {
                    manuscripts.submitReview(req.actingUserId(), req.pathInt(0),
                            req.form("recommendation"), req.form("comments"));
                    return null;
                })

                .get("/api/stats", req -> stats.dashboard(req.actingUserId()));
    }

    private static ManuscriptStatus parseStatus(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ManuscriptStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown status " + value);
        }
    }
}
