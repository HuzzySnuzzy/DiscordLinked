package com.huzzysnuzzy.discordlinked.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a plugin up to date from GitHub releases (see {@link ReleaseClient}): checks every few hours, downloads and
 * stages a newer jar, and restarts the server when the policy says so. What "staging" means is the platform's
 * business (Paper's update folder, or a jar swapped in when Velocity shuts down).
 *
 * <p>Going easy on GitHub: a check is one small download, every {@code checkHours} hours (with up to 10% random
 * delay, so servers started together don't ask together), at most once a minute when asked for, and never sooner
 * than 30 minutes after the last one when the server restarts (a crash loop doesn't turn into a request loop). After a
 * failure the wait doubles from 15 minutes up to a day; when GitHub says to wait, nothing is asked until then.
 *
 * <p>Everything runs on one daemon thread of its own.
 */
public abstract class Updater {

    public enum Mode {
        OFF, NOTIFY, INSTALL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Restart {
        NEVER, WHEN_EMPTY, DAILY;

        public String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * What to do: {@code mode} (off: nothing, notify: only say there's an update, install: download it so it's
     * installed at the next restart), when to restart for a downloaded update, at what time for {@code DAILY}
     * (this machine's time zone), where releases come from and how many hours apart to check.
     */
    public record Policy(Mode mode, Restart restart, LocalTime time, String repository, int checkHours) {

        public static final LocalTime DEFAULT_TIME = LocalTime.of(4, 0);
        public static final int DEFAULT_CHECK_HOURS = 3;

        /** From config text; anything not understood falls back to the default (install, never, 04:00, every 3 h). */
        public static Policy of(String mode, String restart, String time, String repository, String checkHours) {
            return new Policy(parseMode(mode), parseRestart(restart), parseTime(time),
                    ReleaseClient.validRepository(trim(repository)) ? trim(repository) : ReleaseClient.DEFAULT_REPOSITORY,
                    parseHours(checkHours));
        }

        public static Mode parseMode(String text) {
            for (Mode mode : Mode.values()) {
                if (mode.key().equals(trim(text).toLowerCase(Locale.ROOT))) {
                    return mode;
                }
            }
            return Mode.INSTALL;
        }

        public static Restart parseRestart(String text) {
            for (Restart restart : Restart.values()) {
                if (restart.key().equals(trim(text).toLowerCase(Locale.ROOT))) {
                    return restart;
                }
            }
            return Restart.NEVER;
        }

        /** "HH:MM" (24-hour); null if it isn't one. */
        public static LocalTime time(String text) {
            String value = trim(text);
            if (!value.matches("\\d{1,2}:\\d{2}")) {
                return null;
            }
            try {
                return LocalTime.parse(value.length() == 4 ? "0" + value : value);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static LocalTime parseTime(String text) {
            LocalTime time = time(text);
            return time == null ? DEFAULT_TIME : time;
        }

        private static int parseHours(String text) {
            try {
                return Math.max(1, Math.min(168, Integer.parseInt(trim(text))));
            } catch (NumberFormatException e) {
                return DEFAULT_CHECK_HOURS;
            }
        }

        private static String trim(String text) {
            return text == null ? "" : text.strip();
        }

        public String timeText() {
            return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
        }
    }

    public enum State {
        /** Updates are off. */
        DISABLED,
        /** Not checked yet since the server started. */
        WAITING,
        CHECKING,
        UP_TO_DATE,
        /** The repository has no release yet. */
        NO_RELEASE,
        /** Newer one out, but the mode is notify. */
        AVAILABLE,
        DOWNLOADING,
        /** Downloaded and checked: installed at the next restart. */
        READY,
        RESTARTING,
        /** The last check or download failed ({@code error} says why); tried again later. */
        FAILED
    }

    /**
     * What the website shows. {@code latest} = the newest release seen ("" before a successful check), {@code staged}
     * = the version waiting for a restart (""), {@code restartAt} = when a planned restart happens (0 = none planned),
     * {@code restartBlocked} = the server was restarted for {@code staged} already but still runs the old version, so
     * it isn't restarted for it again automatically.
     */
    public record Snapshot(String current, State state, String latest, String latestTag, String staged, String error,
                           long lastCheck, long nextCheck, Policy policy, long restartAt, boolean restartBlocked) {
    }

    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long TICK_SECONDS = 15;
    private static final long MIN_ASKED_GAP = MINUTE;
    private static final long MIN_HINT_GAP = 10 * MINUTE;
    private static final long MIN_GAP_AFTER_START = 30 * MINUTE;
    private static final long FIRST_FAILURE_WAIT = 15 * MINUTE;
    private static final long MAX_WAIT = 24 * HOUR;
    /** A downloaded update waits this long before a when-empty restart, so a check right after start isn't abrupt. */
    private static final long EMPTY_GRACE = 30_000;
    private static final long MANUAL_RESTART_DELAY = 15_000;
    private static final String STATE_FILE = "updater.properties";

    private final ReleaseClient client;
    private final ScheduledExecutorService executor;

    private volatile State state = State.WAITING;
    private volatile ReleaseClient.Release latest;
    private volatile String error = "";
    private volatile long lastCheck;
    private volatile long lastAttempt;
    private volatile long nextCheck;
    private volatile long backoffUntil;
    private volatile int failures;
    private volatile long readySince;
    private volatile long restartAt;
    private volatile boolean restartBlocked;
    /** The staged version a restart was made for, so a restart that didn't install it isn't repeated. */
    private volatile String restartedFor = "";
    private volatile long manualRestartAt;
    private volatile String stagedCache = "";
    private volatile Policy lastPolicy;
    private boolean warned5;
    private boolean warned1;

    protected Updater(String userAgent, String threadName) {
        this.client = new ReleaseClient(userAgent);
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    // ---- What the platform provides ----

    /** The version running now. */
    protected abstract String currentVersion();

    /** Which jar of a release is this plugin's. */
    protected abstract ReleaseClient.Asset asset(ReleaseClient.Release release);

    /** Where downloads and the updater's state file go (created if missing). */
    protected abstract Path workDirectory();

    /** Checks a downloaded jar is this plugin at {@code version}; throws if not. */
    protected abstract void verify(Path jar, String version) throws IOException;

    /** Puts a checked jar where the platform installs it from at the next start (moving it away from {@code jar}). */
    protected abstract void stage(Path jar, ReleaseClient.Release release) throws IOException;

    /** The version waiting to be installed at the next start, or null. */
    protected abstract String staged();

    /** Throws away a staged update (updates switched off, or the server is already on that version). */
    protected abstract void discardStaged();

    /** The current policy (read every 15 seconds, so config changes are picked up). */
    protected abstract Policy policy();

    protected abstract int playersOnline();

    /** Tells the players online that the server restarts in {@code in}. */
    protected abstract void warnPlayers(Duration in);

    /** Restarts (or stops, for the host to start again) the server. Called on the updater's thread. */
    protected abstract void restartServer();

    protected abstract void log(boolean warning, String message);

    /** Why nothing can be downloaded here (null if it can), e.g. the plugin doesn't run from a jar. */
    protected String downloadProblem() {
        return null;
    }

    /** Why a restart wouldn't install the download (null if it would), e.g. the jar swap failed before. */
    protected String restartProblem() {
        return null;
    }

    /**
     * The Java version a class in a jar needs (from its class file version), or -1 if the class isn't there or
     * can't be read. Used to refuse an update this server's Java can't run.
     */
    protected static int javaVersion(Path jar, String className) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(className.replace('.', '/') + ".class");
            if (entry == null) {
                return -1;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] head = in.readNBytes(8);
                if (head.length < 8 || (head[0] & 0xff) != 0xCA || (head[1] & 0xff) != 0xFE
                        || (head[2] & 0xff) != 0xBA || (head[3] & 0xff) != 0xBE) {
                    return -1;
                }
                return (((head[6] & 0xff) << 8) | (head[7] & 0xff)) - 44;
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /** Throws if the jar's main class needs a newer Java than this server runs. */
    protected static void checkJava(Path jar, String mainClass) throws IOException {
        int needs = mainClass == null ? -1 : javaVersion(jar, mainClass);
        int runs = Runtime.version().feature();
        if (needs > runs) {
            throw new IOException("The new version needs Java " + needs + "; this server runs Java " + runs);
        }
    }

    /** After a successful check, with the newest release (e.g. the proxy tells the backend servers about it). */
    protected void released(ReleaseClient.Release release) {
    }

    /** The time now (epoch millis); tests use their own clock. */
    protected long now() {
        return System.currentTimeMillis();
    }

    // ---- Lifecycle ----

    public void start() {
        prepare();
        executor.scheduleWithFixedDelay(this::tickSafely, 5, TICK_SECONDS, TimeUnit.SECONDS);
    }

    /** Reads the state file and what's staged, and plans the first check. */
    void prepare() {
        load();
        long now = now();
        String current = currentVersion();
        if (!restartedFor.isEmpty() && !Version.newer(restartedFor, current)) {
            restartedFor = "";
            save();
        }
        // A while after start (not all servers of a network at once), and not sooner than 30 minutes after the
        // last check, so a server that keeps restarting doesn't keep asking.
        nextCheck = Math.max(Math.max(now + MINUTE + ThreadLocalRandom.current().nextLong(3 * MINUTE),
                Math.max(lastCheck, lastAttempt) + MIN_GAP_AFTER_START),
                Math.max(backoffUntil, failures > 0 ? lastAttempt + failureWait() : 0));
        refreshStaged();
        if (!stagedCache.isEmpty()) {
            if (Version.newer(stagedCache, current)) {
                state = State.READY;
                readySince = now;
            } else {
                discardStaged();
                refreshStaged();
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public Snapshot snapshot() {
        ReleaseClient.Release release = latest;
        Policy policy = lastPolicy != null ? lastPolicy : policy();
        return new Snapshot(currentVersion(), state, release == null ? "" : release.version(),
                release == null ? "" : release.tag(), stagedCache, error, lastCheck, nextCheck, policy,
                manualRestartAt > 0 ? manualRestartAt : restartAt, restartBlocked);
    }

    /**
     * Checks now (from the website or a command). Refused while GitHub asked to wait, within a minute of the last
     * check, or while downloading; the answer says what happens.
     */
    public String checkNow() {
        long now = now();
        if (policy().mode() == Mode.OFF) {
            return "Updates are off.";
        }
        if (state == State.DOWNLOADING || state == State.CHECKING) {
            return "Already checking.";
        }
        if (backoffUntil > now) {
            return "GitHub asked to wait; the next check is in " + duration(backoffUntil - now) + ".";
        }
        if (now - lastAttempt < MIN_ASKED_GAP) {
            return "Checked less than a minute ago.";
        }
        nextCheck = now;
        executor.execute(this::tickSafely);
        return "Checking for updates…";
    }

    /**
     * The proxy saw a newer release: check soon rather than at the next planned check, unless that was asked for
     * in the last 10 minutes or that version is already downloaded.
     */
    public void hint(String version) {
        long now = now();
        if (!Version.valid(version) || policy().mode() == Mode.OFF || !Version.newer(version, currentVersion())
                || (!stagedCache.isEmpty() && !Version.newer(version, stagedCache))
                || now - lastAttempt < MIN_HINT_GAP || backoffUntil > now || state == State.DOWNLOADING
                || (failures > 0 && now < lastAttempt + failureWait())) {
            return;
        }
        nextCheck = Math.min(nextCheck, now + ThreadLocalRandom.current().nextLong(MINUTE));
    }

    /** Restarts in 15 seconds to install the downloaded update. Null if started, else why not. */
    public String restartSoon() {
        if (state != State.READY || stagedCache.isEmpty()) {
            return "There's no downloaded update to install.";
        }
        String problem = restartProblem();
        if (problem != null && !problem.isEmpty()) {
            return problem;
        }
        if (manualRestartAt > 0) {
            return null;
        }
        manualRestartAt = now() + MANUAL_RESTART_DELAY;
        executor.execute(() -> warnPlayers(Duration.ofMillis(MANUAL_RESTART_DELAY)));
        return null;
    }

    // ---- The work ----

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log(true, "Updater error: " + e);
        }
    }

    void tick() {
        Policy policy = policy();
        if (!policy.equals(lastPolicy)) {
            policyChanged(lastPolicy, policy);
            lastPolicy = policy;
        }
        if (policy.mode() == Mode.OFF) {
            return;
        }
        long now = now();
        if (now >= nextCheck && state != State.RESTARTING) {
            check(policy);
        }
        planRestart(policy);
    }

    private void policyChanged(Policy before, Policy now) {
        restartAt = 0;
        warned5 = false;
        warned1 = false;
        if (now.mode() == Mode.OFF) {
            state = State.DISABLED;
            manualRestartAt = 0;
            if (!stagedCache.isEmpty()) {
                discardStaged();
                refreshStaged();
                log(false, "Updates are off: the downloaded update was removed.");
            }
            return;
        }
        if (state == State.DISABLED) {
            state = State.WAITING;
        }
        if (before == null) {
            return;
        }
        // Back to install with a newer release seen than what's downloaded (if anything): get it now.
        ReleaseClient.Release seen = latest;
        boolean installNow = now.mode() == Mode.INSTALL && before.mode() != Mode.INSTALL && seen != null
                && Version.newer(seen.version(), currentVersion())
                && (stagedCache.isEmpty() || Version.newer(seen.version(), stagedCache));
        if (before.mode() == Mode.OFF || installNow || !before.repository().equals(now.repository())) {
            long wait = now() - lastAttempt < MIN_ASKED_GAP ? MIN_ASKED_GAP : 0;
            nextCheck = Math.max(backoffUntil, now() + wait);
        }
    }

    private void check(Policy policy) {
        long now = now();
        lastAttempt = now;
        State before = state;
        state = State.CHECKING;
        try {
            ReleaseClient.Release release = client.latest(policy.repository());
            latest = release;
            lastCheck = now();
            backoffUntil = 0;
            String current = currentVersion();
            refreshStaged();
            if (!Version.newer(release.version(), current)) {
                if (!stagedCache.isEmpty() && !Version.newer(stagedCache, current)) {
                    discardStaged();
                    refreshStaged();
                }
                state = stagedCache.isEmpty() ? State.UP_TO_DATE : State.READY;
            } else if (!stagedCache.isEmpty() && Version.compare(stagedCache, release.version()) >= 0) {
                state = State.READY;
            } else if (policy.mode() == Mode.NOTIFY) {
                if (before != State.AVAILABLE && before != State.READY) {
                    log(false, "Version " + release.version() + " is out (this is " + current + "): "
                            + ReleaseClient.page(policy.repository(), release.tag()));
                }
                // A download from before stays ready (and a restart already asked for still happens).
                state = stagedCache.isEmpty() ? State.AVAILABLE : State.READY;
            } else {
                install(policy, release);
            }
            // Only a check that got all the way (download included) ends a run of failures: a release whose jar
            // keeps failing must not be downloaded again every 15 minutes.
            failures = 0;
            error = "";
            nextCheck = lastCheck + jittered(policy.checkHours() * HOUR);
            released(release);
        } catch (ReleaseClient.NoRelease e) {
            lastCheck = now();
            failures = 0;
            error = e.getMessage();
            nextCheck = lastCheck + jittered(policy.checkHours() * HOUR);
            state = stagedCache.isEmpty() ? State.NO_RELEASE : State.READY;
        } catch (ReleaseClient.RateLimited e) {
            failures++;
            long until = Math.min(now() + MAX_WAIT,
                    Math.max(e.retryAt, now() + FIRST_FAILURE_WAIT));
            backoffUntil = until;
            nextCheck = until;
            error = e.getMessage() + "; trying again in " + duration(until - now());
            state = stagedCache.isEmpty() ? State.FAILED : State.READY;
            log(true, "Update check: " + error);
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: whatever went wrong, the next try waits like after any failure.
            failures++;
            long wait = failureWait();
            nextCheck = now() + wait;
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            state = stagedCache.isEmpty() ? State.FAILED : State.READY;
            // Logged once, not every retry.
            if (failures == 1) {
                log(true, "Update check failed (trying again in " + duration(wait) + "): " + error);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = before;
            return;
        }
        save();
    }

    /** How long to wait after the latest of {@code failures} failures in a row: 15 minutes, doubling, up to a day. */
    private long failureWait() {
        return failures <= 0 ? 0 : Math.min(MAX_WAIT, FIRST_FAILURE_WAIT << Math.min(10, failures - 1));
    }

    private void install(Policy policy, ReleaseClient.Release release) throws IOException, InterruptedException {
        String problem = downloadProblem();
        if (problem != null) {
            throw new IOException(problem);
        }
        state = State.DOWNLOADING;
        ReleaseClient.Asset asset = asset(release);
        Path directory = workDirectory();
        Files.createDirectories(directory);
        Path download = directory.resolve(asset.file() + ".part");
        try {
            client.download(policy.repository(), release, asset, download);
            verify(download, release.version());
            stage(download, release);
        } finally {
            Files.deleteIfExists(download);
        }
        refreshStaged();
        state = State.READY;
        readySince = now();
        restartAt = 0;
        log(false, "Downloaded version " + release.version() + " (this is " + currentVersion()
                + "); it's installed when the server restarts" + switch (policy.restart()) {
                    case NEVER -> ".";
                    case WHEN_EMPTY -> ", which happens as soon as nobody is online.";
                    case DAILY -> ", which happens at " + policy.timeText() + ".";
                });
    }

    private void planRestart(Policy policy) {
        long now = now();
        if (state != State.READY || stagedCache.isEmpty()) {
            restartAt = 0;
            manualRestartAt = 0;
            return;
        }
        if (manualRestartAt > 0) {
            if (now >= manualRestartAt) {
                restart(true);
            }
            return;
        }
        restartBlocked = stagedCache.equals(restartedFor) && Version.newer(stagedCache, currentVersion());
        if (restartBlocked || policy.restart() == Restart.NEVER) {
            restartAt = 0;
            return;
        }
        if (policy.restart() == Restart.WHEN_EMPTY) {
            restartAt = 0;
            if (now - readySince >= EMPTY_GRACE && playersOnline() == 0) {
                restart(false);
            }
            return;
        }
        if (restartAt == 0) {
            restartAt = next(policy.time(), now);
            warned5 = false;
            warned1 = false;
        }
        long left = restartAt - now;
        if (left <= 0) {
            restart(false);
        } else if (left <= MINUTE && !warned1) {
            warned1 = true;
            warned5 = true;
            warnIfAnyone(left);
        } else if (left <= 5 * MINUTE && !warned5) {
            warned5 = true;
            warnIfAnyone(left);
        }
    }

    private void warnIfAnyone(long left) {
        if (playersOnline() > 0) {
            // Rounded to the tick, so "in 5 minutes" rather than "in 4 minutes 52 seconds".
            long rounded = Math.round(left / (double) (TICK_SECONDS * 1000)) * TICK_SECONDS * 1000;
            warnPlayers(Duration.ofMillis(Math.max(TICK_SECONDS * 1000, rounded)));
        }
    }

    private void restart(boolean manual) {
        restartedFor = stagedCache;
        state = State.RESTARTING;
        save();
        log(false, (manual ? "Restarting" : "Restarting automatically") + " to install version " + stagedCache + ".");
        restartServer();
    }

    /** The next time of day {@code time} after {@code now} (at least a minute away, so players can be warned). */
    static long next(LocalTime time, long now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime at = LocalDateTime.of(LocalDate.now(zone), time);
        long candidate = at.atZone(zone).toInstant().toEpochMilli();
        while (candidate - now < MINUTE) {
            at = at.plusDays(1);
            candidate = at.atZone(zone).toInstant().toEpochMilli();
        }
        return candidate;
    }

    private static long jittered(long millis) {
        return millis + ThreadLocalRandom.current().nextLong(Math.max(1, millis / 10));
    }

    private void refreshStaged() {
        String staged = staged();
        stagedCache = staged == null ? "" : staged;
    }

    /** One line on what the updater is doing, for commands and the console. */
    public static String describe(Snapshot s) {
        long now = System.currentTimeMillis();
        return switch (s.state()) {
            case DISABLED -> "updates are off";
            case WAITING -> "the first check is in " + duration(Math.max(0, s.nextCheck() - now));
            case CHECKING -> "checking for updates";
            case DOWNLOADING -> "downloading " + s.latest();
            case UP_TO_DATE -> "up to date";
            case NO_RELEASE -> "no release published yet";
            case AVAILABLE -> s.latest() + " is out (updates are set to notify)";
            case RESTARTING -> "restarting to install " + s.staged();
            case FAILED -> "the last check failed: " + s.error();
            case READY -> s.staged() + " is downloaded and installed at the next restart" + (s.restartBlocked()
                    ? " (a restart didn't install it, so it isn't restarted for it again)"
                    : s.restartAt() > 0 ? " (in " + duration(s.restartAt() - now) + ")"
                    : s.policy().restart() == Restart.WHEN_EMPTY ? " (as soon as nobody is online)" : "");
        };
    }

    /** "5 minutes", "1 hour 30 minutes", "15 seconds". */
    public static String duration(long millis) {
        long seconds = Math.max(1, Math.round(millis / 1000.0));
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + (hours == 1 ? " hour" : " hours") + (rest == 0 ? "" : " " + rest + (rest == 1 ? " minute" : " minutes"));
    }

    // ---- The state file (when it last checked, waits, and what a restart was for) ----

    private void load() {
        Path file = workDirectory().resolve(STATE_FILE);
        if (!Files.exists(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            lastCheck = number(props, "last-check");
            backoffUntil = number(props, "wait-until");
            failures = (int) number(props, "failures");
            lastAttempt = number(props, "last-attempt");
            restartedFor = props.getProperty("restarted-for", "").strip();
        } catch (IOException | IllegalArgumentException e) {
            log(true, "Could not read " + file + ": " + e.getMessage());
        }
        long now = now();
        // A clock that jumped back must not stop checks for ages.
        lastCheck = Math.min(lastCheck, now);
        lastAttempt = Math.min(lastAttempt, now);
        backoffUntil = Math.min(backoffUntil, now + MAX_WAIT);
    }

    private static long number(Properties props, String key) {
        try {
            return Math.max(0, Long.parseLong(props.getProperty(key, "0").strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void save() {
        Properties props = new Properties();
        props.setProperty("last-check", Long.toString(lastCheck));
        props.setProperty("wait-until", Long.toString(backoffUntil));
        props.setProperty("failures", Integer.toString(failures));
        props.setProperty("last-attempt", Long.toString(lastAttempt));
        props.setProperty("restarted-for", restartedFor);
        try {
            Path directory = workDirectory();
            Files.createDirectories(directory);
            Path temp = directory.resolve(STATE_FILE + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                props.store(out, "DiscordLinked updater state (written by the plugin)");
            }
            Files.move(temp, directory.resolve(STATE_FILE), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log(true, "Could not save the updater's state: " + e.getMessage());
        }
    }

    /** A small text file inside a jar (e.g. its plugin.yml), or null if it isn't there. Throws if it isn't a jar. */
    protected static String readEntry(Path jar, String name) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(64 << 10);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException(jar.getFileName() + " isn't a valid jar: " + e.getMessage());
        }
    }

    /** Runs something on the updater's thread (e.g. a platform's follow-up work). */
    protected void later(Runnable task) {
        executor.execute(task);
    }
}
