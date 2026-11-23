package com.pubtrack.service;

import com.pubtrack.db.Database;
import com.pubtrack.db.DatabaseInitializer;
import com.pubtrack.exception.ConflictException;
import com.pubtrack.exception.ForbiddenException;
import com.pubtrack.exception.InvalidTransitionException;
import com.pubtrack.exception.ValidationException;
import com.pubtrack.model.Manuscript;
import com.pubtrack.model.ManuscriptDetail;
import com.pubtrack.model.ManuscriptStatus;
import com.pubtrack.model.Page;
import com.pubtrack.model.ReviewAssignment;
import com.pubtrack.repository.JournalRepository;
import com.pubtrack.repository.ManuscriptRepository;
import com.pubtrack.repository.ReviewRepository;
import com.pubtrack.repository.StatsRepository;
import com.pubtrack.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the workflow against a real MySQL database.
 * Runs only when PUBTRACK_TEST_DB_URL is set, e.g.
 * PUBTRACK_TEST_DB_URL=jdbc:mysql://localhost:3306/pubtrack_test mvn test
 * (the database is wiped and re-seeded before every test, so never point it at real data).
 */
@EnabledIfEnvironmentVariable(named = "PUBTRACK_TEST_DB_URL", matches = ".+")
class WorkflowIntegrationTest {

    // Seeded users (see seed.sql)
    private static final int EDITOR = 1;
    private static final int AUTHOR_PRIYA = 3;
    private static final int AUTHOR_RAHUL = 4;
    private static final int REVIEWER_ARJUN = 6;
    private static final int REVIEWER_SARAH = 7;
    private static final int REVIEWER_KAVYA = 8;

    private static Database db;
    private static ManuscriptService service;
    private static StatsService stats;
    private static ReviewReminderScheduler reminders;

    @BeforeAll
    static void connect() {
        String user = System.getenv().getOrDefault("PUBTRACK_TEST_DB_USER", "pubtrack");
        String password = System.getenv().getOrDefault("PUBTRACK_TEST_DB_PASSWORD", "pubtrack123");
        db = new Database(System.getenv("PUBTRACK_TEST_DB_URL"), user, password, 3);
        UserRepository users = new UserRepository();
        ReviewRepository reviews = new ReviewRepository();
        service = new ManuscriptService(db, users, new JournalRepository(), new ManuscriptRepository(), reviews);
        stats = new StatsService(db, users, new StatsRepository());
        reminders = new ReviewReminderScheduler(db, reviews);
    }

    @AfterAll
    static void disconnect() {
        db.close();
    }

    @BeforeEach
    void resetDatabase() {
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                for (String table : List.of("status_history", "review_assignments", "manuscripts", "users", "journals")) {
                    st.execute("DROP TABLE IF EXISTS " + table);
                }
            }
            return null;
        });
        DatabaseInitializer.initialize(db, true);
    }

    private int submitPaper(int authorId) {
        return service.submit(authorId, 1, "A Study of Connection Pooling in Java Web Servers",
                "We measure how pool size, validation strategy and timeout settings affect latency under load.",
                "java, jdbc, performance").id();
    }

    @Test
    void fullHappyPathFromSubmissionToAcceptance() {
        int id = submitPaper(AUTHOR_PRIYA);
        assertEquals(ManuscriptStatus.SUBMITTED, service.getDetail(EDITOR, id).manuscript().status());

        service.assignReviewer(EDITOR, id, REVIEWER_ARJUN);
        service.assignReviewer(EDITOR, id, REVIEWER_SARAH);
        ManuscriptDetail afterAssign = service.getDetail(EDITOR, id);
        assertEquals(ManuscriptStatus.UNDER_REVIEW, afterAssign.manuscript().status());
        assertEquals(2, afterAssign.manuscript().reviewsTotal());
        assertFalse(afterAssign.actions().contains("DECIDE"), "cannot decide while reviews are pending");

        assertThrows(ValidationException.class, () -> service.decide(EDITOR, id, "ACCEPT", null));

        for (int reviewer : new int[] {REVIEWER_ARJUN, REVIEWER_SARAH}) {
            String action = service.getDetail(reviewer, id).actions().get(0);
            int assignmentId = Integer.parseInt(action.substring("REVIEW:".length()));
            service.submitReview(reviewer, assignmentId, "accept", "Well designed experiments and clear writing.");
        }
        assertTrue(service.getDetail(EDITOR, id).actions().contains("DECIDE"));

        service.decide(EDITOR, id, "ACCEPT", "Congratulations");
        ManuscriptDetail done = service.getDetail(AUTHOR_PRIYA, id);
        assertEquals(ManuscriptStatus.ACCEPTED, done.manuscript().status());
        assertNotNull(done.manuscript().decidedAt());
        assertEquals(List.of(ManuscriptStatus.SUBMITTED, ManuscriptStatus.UNDER_REVIEW, ManuscriptStatus.ACCEPTED),
                done.history().stream().map(h -> h.toStatus()).toList());
        assertTrue(done.actions().isEmpty(), "nothing left to do on an accepted paper");
    }

    @Test
    void keywordsAreOptional() {
        Manuscript m = service.submit(AUTHOR_RAHUL, 2, "Wetland Restoration Outcomes in Coastal Kerala",
                "Ten years of vegetation and bird-count surveys at restored and untouched wetland sites.", "  ");
        assertEquals("", m.keywords());
        assertThrows(ValidationException.class, () -> service.submit(AUTHOR_RAHUL, 2, "Too short", "x", null));
    }

    @Test
    void authorsSeeAnonymousReviewsOnlyAfterDecision() {
        // Seeded manuscript 4 (Priya) has REVISION_REQUESTED with two completed reviews.
        List<ReviewAssignment> visible = service.getDetail(AUTHOR_PRIYA, 4).reviews();
        assertEquals(2, visible.size());
        assertEquals("Reviewer 1", visible.get(0).reviewerName());
        assertNull(visible.get(0).reviewerId());

        // Seeded manuscript 6 (Priya) is UNDER_REVIEW: nothing visible yet.
        assertTrue(service.getDetail(AUTHOR_PRIYA, 6).reviews().isEmpty());
    }

    @Test
    void accessRulesAreEnforced() {
        // Rahul cannot open Priya's paper; a reviewer cannot open a paper they were not invited to.
        assertThrows(ForbiddenException.class, () -> service.getDetail(AUTHOR_RAHUL, 4));
        assertThrows(ForbiddenException.class, () -> service.getDetail(REVIEWER_SARAH, 4));
        // Only editors assign reviewers; only authors submit.
        assertThrows(ForbiddenException.class, () -> service.assignReviewer(AUTHOR_PRIYA, 5, REVIEWER_ARJUN));
        assertThrows(ForbiddenException.class, () -> submitPaper(EDITOR));
        // A reviewer cannot submit someone else's review (assignment 3 belongs to Arjun).
        assertThrows(ForbiddenException.class,
                () -> service.submitReview(REVIEWER_SARAH, 3, "ACCEPT", "Looks great to me, accept it."));
    }

    @Test
    void listVisibilityDependsOnRole() {
        Page<Manuscript> editorView = service.search(EDITOR, null, null, null, 1);
        Page<Manuscript> priyaView = service.search(AUTHOR_PRIYA, null, null, null, 1);
        Page<Manuscript> kavyaView = service.search(REVIEWER_KAVYA, null, null, null, 1);
        assertEquals(10, editorView.total());
        assertEquals(4, priyaView.total());
        assertTrue(priyaView.items().stream().allMatch(m -> m.authorId() == AUTHOR_PRIYA));
        assertEquals(3, kavyaView.total());
    }

    @Test
    void duplicateAssignmentAndTooManyReviewersAreRejected() {
        service.assignReviewer(EDITOR, 5, REVIEWER_ARJUN);
        assertThrows(ConflictException.class, () -> service.assignReviewer(EDITOR, 5, REVIEWER_ARJUN));
        service.assignReviewer(EDITOR, 5, REVIEWER_SARAH);
        service.assignReviewer(EDITOR, 5, REVIEWER_KAVYA);
        assertThrows(ValidationException.class, () -> service.assignReviewer(EDITOR, 5, 9));
        assertThrows(ValidationException.class, () -> service.assignReviewer(EDITOR, 5, AUTHOR_PRIYA));
    }

    @Test
    void illegalTransitionsAreBlocked() {
        // Cannot accept a paper nobody has reviewed.
        assertThrows(InvalidTransitionException.class, () -> service.decide(EDITOR, 5, "ACCEPT", null));
        // Cannot resubmit a paper that is not waiting for a revision.
        assertThrows(InvalidTransitionException.class, () -> service.resubmit(AUTHOR_RAHUL, 5, null));
        // Cannot withdraw an accepted paper (manuscript 1 is ACCEPTED).
        assertThrows(InvalidTransitionException.class, () -> service.withdraw(AUTHOR_PRIYA, 1, null));
        // Desk rejection of a fresh submission is fine.
        service.decide(EDITOR, 5, "REJECT", "Out of scope");
        assertEquals(ManuscriptStatus.REJECTED, service.getDetail(EDITOR, 5).manuscript().status());
    }

    @Test
    void revisionStartsANewReviewRound() {
        service.resubmit(AUTHOR_PRIYA, 4, "Added transfer patients to the cohort");
        Manuscript m = service.getDetail(EDITOR, 4).manuscript();
        assertEquals(ManuscriptStatus.SUBMITTED, m.status());
        assertEquals(2, m.currentRound());
        assertEquals(0, m.reviewsTotal(), "round 1 reviews do not count towards round 2");

        // The same reviewer may review the new round.
        service.assignReviewer(EDITOR, 4, REVIEWER_KAVYA);
        assertEquals(1, service.getDetail(EDITOR, 4).manuscript().reviewsTotal());
        // The author still sees the two round-1 reviews.
        assertEquals(2, service.getDetail(AUTHOR_PRIYA, 4).reviews().size());
    }

    @Test
    void withdrawingCancelsPendingReviews() {
        // Seeded manuscript 6 has one pending review by reviewer 9.
        service.withdraw(AUTHOR_PRIYA, 6, null);
        assertEquals(ManuscriptStatus.WITHDRAWN, service.getDetail(EDITOR, 6).manuscript().status());
        assertTrue(service.getDetail(EDITOR, 6).reviews().isEmpty());
    }

    @Test
    void remindersAreSentOncePerOverdueReview() {
        assertEquals(2, reminders.runOnce(), "seed data has two overdue reviews");
        assertEquals(0, reminders.runOnce(), "already reminded");
    }

    @Test
    void fullTextSearchIgnoresStopWordsAndMatchesPrefixes() {
        assertEquals(1, service.search(EDITOR, null, null, "graph neural for citation", 1).total());
        assertEquals(2, service.search(EDITOR, null, null, "learn", 1).total()); // learning (x2)
        assertEquals(1, service.search(EDITOR, ManuscriptStatus.ACCEPTED, 3, "federated", 1).total());
        assertEquals(0, service.search(EDITOR, null, null, "blockchain", 1).total());
    }

    @Test
    void dashboardAggregatesAreCorrect() {
        Map<String, Object> dashboard = stats.dashboard(EDITOR);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> journals = (List<Map<String, Object>>) dashboard.get("journals");
        Map<String, Object> computing = journals.stream()
                .filter(j -> "Journal of Applied Computing".equals(j.get("name"))).findFirst().orElseThrow();
        assertEquals(3L, ((Number) computing.get("total")).longValue());
        assertEquals(1L, ((Number) computing.get("accepted")).longValue());
        assertEquals(2, ((List<?>) dashboard.get("overdue")).size());
        assertThrows(ForbiddenException.class, () -> stats.dashboard(AUTHOR_PRIYA));
    }
}
