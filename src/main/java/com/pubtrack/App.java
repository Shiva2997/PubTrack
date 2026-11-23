package com.pubtrack;

import com.pubtrack.config.AppConfig;
import com.pubtrack.db.Database;
import com.pubtrack.db.DatabaseInitializer;
import com.pubtrack.repository.JournalRepository;
import com.pubtrack.repository.ManuscriptRepository;
import com.pubtrack.repository.ReviewRepository;
import com.pubtrack.repository.StatsRepository;
import com.pubtrack.repository.UserRepository;
import com.pubtrack.service.ManuscriptService;
import com.pubtrack.service.ReviewReminderScheduler;
import com.pubtrack.service.StatsService;
import com.pubtrack.web.ApiHandler;
import com.pubtrack.web.ApiRoutes;
import com.pubtrack.web.StaticFileHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Wires everything together by hand (no framework) and starts the HTTP server. */
public final class App {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    private App() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT %4$-7s %3$s - %5$s%6$s%n");
        AppConfig config = AppConfig.load();

        Database db = new Database(config.get("db.url"), config.get("db.user"), config.get("db.password"),
                config.getInt("db.pool.size"));
        DatabaseInitializer.initialize(db, config.getBoolean("db.seed"));

        UserRepository userRepo = new UserRepository();
        ReviewRepository reviewRepo = new ReviewRepository();
        ManuscriptService manuscripts = new ManuscriptService(db, userRepo, new JournalRepository(),
                new ManuscriptRepository(), reviewRepo);
        StatsService stats = new StatsService(db, userRepo, new StatsRepository());

        ReviewReminderScheduler reminders = new ReviewReminderScheduler(db, reviewRepo);
        reminders.start(config.getInt("reminders.interval.minutes"));

        int port = config.getInt("server.port");
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", new ApiHandler(ApiRoutes.build(manuscripts, stats)));
        server.createContext("/", new StaticFileHandler("/static"));
        ExecutorService workers = Executors.newFixedThreadPool(config.getInt("server.threads"));
        server.setExecutor(workers);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down");
            server.stop(1);
            workers.shutdown();
            reminders.close();
            db.close();
        }, "shutdown"));

        LOG.info("PubTrack is running at http://localhost:" + port);
    }
}
