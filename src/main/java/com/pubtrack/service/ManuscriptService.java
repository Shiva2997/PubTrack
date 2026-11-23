package com.pubtrack.service;

import com.pubtrack.db.Database;
import com.pubtrack.exception.ConflictException;
import com.pubtrack.exception.ForbiddenException;
import com.pubtrack.exception.InvalidTransitionException;
import com.pubtrack.exception.NotFoundException;
import com.pubtrack.exception.UnauthorizedException;
import com.pubtrack.exception.ValidationException;
import com.pubtrack.model.Decision;
import com.pubtrack.model.Journal;
import com.pubtrack.model.Manuscript;
import com.pubtrack.model.ManuscriptDetail;
import com.pubtrack.model.ManuscriptStatus;
import com.pubtrack.model.Page;
import com.pubtrack.model.Recommendation;
import com.pubtrack.model.ReviewAssignment;
import com.pubtrack.model.Role;
import com.pubtrack.model.SearchCriteria;
import com.pubtrack.model.User;
import com.pubtrack.repository.JournalRepository;
import com.pubtrack.repository.ManuscriptRepository;
import com.pubtrack.repository.ReviewRepository;
import com.pubtrack.repository.UserRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All business rules of the peer-review workflow live here.
 *
 * <p>Every write runs in one database transaction and first locks the manuscript row
 * ({@code SELECT ... FOR UPDATE}), so concurrent requests on the same manuscript cannot
 * interleave. Status changes go through {@link #changeStatus}, which checks the state
 * machine in {@link ManuscriptStatus} and records an audit-trail row.
 */
public class ManuscriptService {

    public static final int PAGE_SIZE = 10;
    public static final int MAX_REVIEWERS_PER_ROUND = 3;

    private final Database db;
    private final UserRepository users;
    private final JournalRepository journals;
    private final ManuscriptRepository manuscripts;
    private final ReviewRepository reviews;

    public ManuscriptService(Database db, UserRepository users, JournalRepository journals,
                             ManuscriptRepository manuscripts, ReviewRepository reviews) {
        this.db = db;
        this.users = users;
        this.journals = journals;
        this.manuscripts = manuscripts;
        this.reviews = reviews;
    }

    // ------------------------------------------------------------------ reads

    public List<User> listUsers() {
        return db.withConnection(users::findAll);
    }

    public List<Journal> listJournals() {
        return db.withConnection(journals::findAll);
    }

    public Page<Manuscript> search(int actingUserId, ManuscriptStatus status, Integer journalId, String text, int page) {
        if (page < 1) {
            throw new ValidationException("Page must be 1 or greater");
        }
        return db.withConnection(conn -> {
            User viewer = requireUser(conn, actingUserId);
            return manuscripts.search(conn, new SearchCriteria(viewer, status, journalId, text, page, PAGE_SIZE));
        });
    }

    public ManuscriptDetail getDetail(int actingUserId, int manuscriptId) {
        return db.withConnection(conn -> {
            User viewer = requireUser(conn, actingUserId);
            Manuscript m = requireManuscript(conn, manuscriptId);
            requireCanView(conn, viewer, m);

            List<ReviewAssignment> visibleReviews = visibleReviews(viewer, m, reviews.findByManuscript(conn, m.id()));
            List<String> actions = availableActions(conn, viewer, m);
            return new ManuscriptDetail(m, visibleReviews, manuscripts.findHistory(conn, m.id()), actions);
        });
    }

    public List<ReviewAssignment> myReviews(int actingUserId) {
        return db.withConnection(conn -> {
            User reviewer = requireUser(conn, actingUserId);
            requireRole(reviewer, Role.REVIEWER, "Only reviewers have review assignments");
            return reviews.findByReviewer(conn, reviewer.id());
        });
    }

    // ----------------------------------------------------------------- writes

    public Manuscript submit(int actingUserId, int journalId, String title, String abstractText, String keywords) {
        String cleanTitle = Validation.text("Title", title, 10, 300);
        String cleanAbstract = Validation.text("Abstract", abstractText, 50, 5000);
        String cleanKeywords = Validation.optionalText("Keywords", keywords, 300);
        if (cleanKeywords == null) {
            cleanKeywords = ""; // column is NOT NULL DEFAULT ''
        }

        String finalKeywords = cleanKeywords;
        return db.inTransaction(conn -> {
            User author = requireUser(conn, actingUserId);
            requireRole(author, Role.AUTHOR, "Only authors can submit manuscripts");
            journals.findById(conn, journalId)
                    .orElseThrow(() -> new NotFoundException("Journal " + journalId + " not found"));

            int id = manuscripts.insert(conn, journalId, author.id(), cleanTitle, cleanAbstract, finalKeywords);
            manuscripts.addHistory(conn, id, null, ManuscriptStatus.SUBMITTED, author.id(), "Initial submission");
            return requireManuscript(conn, id);
        });
    }

    public void assignReviewer(int actingUserId, int manuscriptId, int reviewerId) {
        db.inTransaction(conn -> {
            User editor = requireUser(conn, actingUserId);
            requireRole(editor, Role.EDITOR, "Only editors can assign reviewers");
            Manuscript m = lockAndLoad(conn, manuscriptId);

            if (m.status() != ManuscriptStatus.SUBMITTED && m.status() != ManuscriptStatus.UNDER_REVIEW) {
                throw new InvalidTransitionException("Reviewers can only be assigned while a manuscript is "
                        + "SUBMITTED or UNDER_REVIEW (it is " + m.status() + ")");
            }
            User reviewer = users.findById(conn, reviewerId)
                    .orElseThrow(() -> new NotFoundException("User " + reviewerId + " not found"));
            if (!reviewer.is(Role.REVIEWER)) {
                throw new ValidationException(reviewer.fullName() + " is not a reviewer");
            }
            if (reviews.isAssigned(conn, m.id(), reviewer.id(), m.currentRound())) {
                throw new ConflictException(reviewer.fullName() + " is already reviewing this round");
            }
            if (m.reviewsTotal() >= MAX_REVIEWERS_PER_ROUND) {
                throw new ValidationException("A round can have at most " + MAX_REVIEWERS_PER_ROUND + " reviewers");
            }

            Journal journal = journals.findById(conn, m.journalId()).orElseThrow();
            try {
                reviews.insert(conn, m.id(), reviewer.id(), m.currentRound(), journal.reviewDays());
            } catch (SQLException e) {
                // The unique key (manuscript, reviewer, round) is the final guard against double assignment.
                if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                    throw new ConflictException(reviewer.fullName() + " is already reviewing this round");
                }
                throw e;
            }
            if (m.status() == ManuscriptStatus.SUBMITTED) {
                changeStatus(conn, m, ManuscriptStatus.UNDER_REVIEW, editor,
                        "Reviewer assigned: " + reviewer.fullName());
            }
            return null;
        });
    }

    public void submitReview(int actingUserId, int assignmentId, String recommendation, String comments) {
        Recommendation rec = Validation.enumValue("Recommendation", recommendation, Recommendation.class);
        String cleanComments = Validation.text("Comments", comments, 20, 5000);

        db.inTransaction(conn -> {
            User reviewer = requireUser(conn, actingUserId);
            ReviewAssignment a = reviews.findById(conn, assignmentId, true)
                    .orElseThrow(() -> new NotFoundException("Review assignment " + assignmentId + " not found"));
            if (a.reviewerId() == null || a.reviewerId() != reviewer.id()) {
                throw new ForbiddenException("This review is assigned to someone else");
            }
            if (a.isCompleted()) {
                throw new ConflictException("This review has already been submitted");
            }
            Manuscript m = lockAndLoad(conn, a.manuscriptId());
            if (m.status() != ManuscriptStatus.UNDER_REVIEW || a.round() != m.currentRound()) {
                throw new InvalidTransitionException("This manuscript is no longer accepting reviews for round "
                        + a.round());
            }
            reviews.complete(conn, a.id(), rec, cleanComments);
            return null;
        });
    }

    public void decide(int actingUserId, int manuscriptId, String decision, String note) {
        Decision d = Validation.enumValue("Decision", decision, Decision.class);
        String cleanNote = Validation.optionalText("Note", note, 500);

        db.inTransaction(conn -> {
            User editor = requireUser(conn, actingUserId);
            requireRole(editor, Role.EDITOR, "Only editors can make decisions");
            Manuscript m = lockAndLoad(conn, manuscriptId);

            if (m.status() == ManuscriptStatus.SUBMITTED) {
                if (d != Decision.REJECT) {
                    throw new InvalidTransitionException("A manuscript must be reviewed before it can be "
                            + (d == Decision.ACCEPT ? "accepted" : "sent back for revision"));
                }
            } else if (m.status() == ManuscriptStatus.UNDER_REVIEW) {
                int[] counts = reviews.countByState(conn, m.id(), m.currentRound());
                if (counts[1] > 0) {
                    throw new ValidationException(counts[1] + " review(s) still pending in round " + m.currentRound());
                }
                if (counts[0] == 0) {
                    throw new ValidationException("At least one completed review is needed before deciding");
                }
            }
            String defaultNote = m.status() == ManuscriptStatus.SUBMITTED ? "Desk rejected" : "Editorial decision: " + d;
            changeStatus(conn, m, d.resultingStatus(), editor, cleanNote == null ? defaultNote : cleanNote);
            return null;
        });
    }

    public void resubmit(int actingUserId, int manuscriptId, String note) {
        String cleanNote = Validation.optionalText("Note", note, 500);
        db.inTransaction(conn -> {
            User author = requireUser(conn, actingUserId);
            Manuscript m = lockAndLoad(conn, manuscriptId);
            requireOwner(author, m);
            if (m.status() != ManuscriptStatus.REVISION_REQUESTED) {
                throw new InvalidTransitionException("Only manuscripts with a revision request can be resubmitted");
            }
            manuscripts.startNextRound(conn, m.id());
            changeStatus(conn, m, ManuscriptStatus.SUBMITTED, author,
                    cleanNote == null ? "Revised version submitted (round " + (m.currentRound() + 1) + ")" : cleanNote);
            return null;
        });
    }

    public void withdraw(int actingUserId, int manuscriptId, String note) {
        String cleanNote = Validation.optionalText("Note", note, 500);
        db.inTransaction(conn -> {
            User author = requireUser(conn, actingUserId);
            Manuscript m = lockAndLoad(conn, manuscriptId);
            requireOwner(author, m);
            changeStatus(conn, m, ManuscriptStatus.WITHDRAWN, author,
                    cleanNote == null ? "Withdrawn by author" : cleanNote);
            reviews.deletePending(conn, m.id()); // cancel open invitations so nobody is reminded about them
            return null;
        });
    }

    // ---------------------------------------------------------------- helpers

    /** The only place a status ever changes: validates the transition and writes the audit trail. */
    private void changeStatus(Connection conn, Manuscript m, ManuscriptStatus target, User actor, String note)
            throws SQLException {
        if (!m.status().canMoveTo(target)) {
            throw new InvalidTransitionException(m.status(), target);
        }
        if (!manuscripts.updateStatus(conn, m.id(), m.status(), target)) {
            throw new ConflictException("The manuscript was changed by someone else. Please reload and try again.");
        }
        manuscripts.addHistory(conn, m.id(), m.status(), target, actor.id(), note);
    }

    /**
     * Decides which reviews the viewer may see.
     * Editors: all. Reviewers: only their own. Authors: completed reviews from rounds that already
     * have a decision, with reviewer names hidden (blind peer review).
     */
    private List<ReviewAssignment> visibleReviews(User viewer, Manuscript m, List<ReviewAssignment> all) {
        return switch (viewer.role()) {
            case EDITOR -> all;
            case REVIEWER -> all.stream().filter(r -> r.reviewerId() != null && r.reviewerId() == viewer.id()).toList();
            case AUTHOR -> {
                boolean currentRoundDecided = m.status() == ManuscriptStatus.REVISION_REQUESTED || m.status().isDecision();
                Map<Integer, Integer> counterPerRound = new HashMap<>();
                List<ReviewAssignment> visible = new ArrayList<>();
                for (ReviewAssignment r : all) {
                    boolean roundDecided = r.round() < m.currentRound() || currentRoundDecided;
                    if (r.isCompleted() && roundDecided) {
                        int n = counterPerRound.merge(r.round(), 1, Integer::sum);
                        visible.add(r.anonymised("Reviewer " + n));
                    }
                }
                yield visible;
            }
        };
    }

    /** What the viewer can do next. The UI shows buttons based on this list. */
    private List<String> availableActions(Connection conn, User viewer, Manuscript m) throws SQLException {
        List<String> actions = new ArrayList<>();
        ManuscriptStatus s = m.status();
        switch (viewer.role()) {
            case EDITOR -> {
                if (s == ManuscriptStatus.SUBMITTED || s == ManuscriptStatus.UNDER_REVIEW) {
                    if (m.reviewsTotal() < MAX_REVIEWERS_PER_ROUND) {
                        actions.add("ASSIGN_REVIEWER");
                    }
                }
                if (s == ManuscriptStatus.SUBMITTED) {
                    actions.add("DESK_REJECT");
                }
                if (s == ManuscriptStatus.UNDER_REVIEW) {
                    int[] counts = reviews.countByState(conn, m.id(), m.currentRound());
                    if (counts[0] > 0 && counts[1] == 0) {
                        actions.add("DECIDE");
                    }
                }
            }
            case AUTHOR -> {
                if (m.authorId() == viewer.id()) {
                    if (s == ManuscriptStatus.REVISION_REQUESTED) {
                        actions.add("RESUBMIT");
                    }
                    if (!s.isTerminal()) {
                        actions.add("WITHDRAW");
                    }
                }
            }
            case REVIEWER -> {
                if (s == ManuscriptStatus.UNDER_REVIEW) {
                    for (ReviewAssignment r : reviews.findByManuscript(conn, m.id())) {
                        if (r.reviewerId() != null && r.reviewerId() == viewer.id()
                                && r.round() == m.currentRound() && !r.isCompleted()) {
                            actions.add("REVIEW:" + r.id());
                        }
                    }
                }
            }
        }
        return actions;
    }

    private Manuscript lockAndLoad(Connection conn, int manuscriptId) throws SQLException {
        if (!manuscripts.lock(conn, manuscriptId)) {
            throw new NotFoundException("Manuscript " + manuscriptId + " not found");
        }
        return requireManuscript(conn, manuscriptId);
    }

    private Manuscript requireManuscript(Connection conn, int id) throws SQLException {
        return manuscripts.findById(conn, id)
                .orElseThrow(() -> new NotFoundException("Manuscript " + id + " not found"));
    }

    private User requireUser(Connection conn, int id) throws SQLException {
        return users.findById(conn, id)
                .orElseThrow(() -> new UnauthorizedException("Unknown user " + id));
    }

    private static void requireRole(User user, Role role, String message) {
        if (!user.is(role)) {
            throw new ForbiddenException(message);
        }
    }

    private static void requireOwner(User user, Manuscript m) {
        if (!user.is(Role.AUTHOR) || m.authorId() != user.id()) {
            throw new ForbiddenException("Only the submitting author can do this");
        }
    }

    private void requireCanView(Connection conn, User viewer, Manuscript m) throws SQLException {
        boolean allowed = switch (viewer.role()) {
            case EDITOR -> true;
            case AUTHOR -> m.authorId() == viewer.id();
            case REVIEWER -> reviews.isAssigned(conn, m.id(), viewer.id(), null);
        };
        if (!allowed) {
            throw new ForbiddenException("You do not have access to this manuscript");
        }
    }
}
