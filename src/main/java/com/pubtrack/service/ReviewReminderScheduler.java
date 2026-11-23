package com.pubtrack.service;

import com.pubtrack.db.Database;
import com.pubtrack.model.OverdueReminder;
import com.pubtrack.repository.ReviewRepository;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background job that finds overdue reviews and "emails" each reviewer once.
 * (The email is logged; plugging in JavaMail/SMTP would only change {@link #send}.)
 *
 * <p>Runs on its own single daemon thread via {@link ScheduledExecutorService}.
 */
public class ReviewReminderScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ReviewReminderScheduler.class.getName());

    private final Database db;
    private final ReviewRepository reviews;
    private final ScheduledExecutorService executor;

    public ReviewReminderScheduler(Database db, ReviewRepository reviews) {
        this.db = db;
        this.reviews = reviews;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "review-reminders");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(long intervalMinutes) {
        executor.scheduleAtFixedRate(this::runSafely, 5, TimeUnit.MINUTES.toSeconds(intervalMinutes), TimeUnit.SECONDS);
        LOG.info("Overdue-review reminders scheduled every " + intervalMinutes + " minute(s)");
    }

    /**
     * scheduleAtFixedRate silently stops repeating if a run throws, so every run is wrapped
     * and failures are only logged.
     */
    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Reminder job failed; will retry next run", e);
        }
    }

    /** Sends reminders for all overdue, not-yet-reminded reviews. Returns how many were sent. */
    public int runOnce() {
        return db.inTransaction(conn -> {
            List<OverdueReminder> due = reviews.findOverdueWithoutReminder(conn);
            if (due.isEmpty()) {
                return 0;
            }
            due.forEach(this::send);
            reviews.markReminderSent(conn, due.stream().map(OverdueReminder::assignmentId).toList());
            return due.size();
        });
    }

    private void send(OverdueReminder r) {
        LOG.info(String.format("REMINDER -> %s <%s>: your review of \"%s\" was due on %s",
                r.reviewerName(), r.reviewerEmail(), r.manuscriptTitle(), r.dueAt().toLocalDate()));
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
