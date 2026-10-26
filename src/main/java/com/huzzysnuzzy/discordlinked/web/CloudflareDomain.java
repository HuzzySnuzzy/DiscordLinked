package com.huzzysnuzzy.discordlinked.web;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the website on the owner's own domain (e.g. dla.example.com) with a permanent Cloudflare tunnel, set up from
 * the Settings page without typing commands:
 * <ol>
 *   <li>{@code cloudflared tunnel login} prints a Cloudflare link; the owner opens it, picks their domain and
 *       authorizes. cloudflared then saves a certificate for that domain.</li>
 *   <li>{@code cloudflared tunnel create} makes the tunnel (its credentials stay in plugins/discordlinked/cloudflare/),
 *       and {@code cloudflared tunnel route dns} adds the DNS record pointing the address at it.</li>
 *   <li>The website then runs that tunnel instead of a quick tunnel (see {@link CloudflareTunnel#named}).</li>
 * </ol>
 * Everything is kept in plugins/discordlinked/cloudflare/; nothing is written to the user's home folder.
 */
final class CloudflareDomain {

    enum State { NONE, WAITING, WORKING, READY, ERROR }

    private static final Pattern LOGIN_URL = Pattern.compile("https://dash\\.cloudflare\\.com/argotunnel\\S+");
    private static final Pattern TUNNEL_ID = Pattern.compile("with id ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final Path directory;
    private final Path home;
    private final Path certificate;
    private final Path credentials;
    private final Path stateFile;
    private final Path binDirectory;
    private final Logger logger;
    /** Called once a tunnel and its address are ready (or the address changed): switch the website over. */
    private final Consumer<CloudflareDomain> onReady;

    private volatile State state;
    private volatile String loginUrl;
    private volatile String message = "";
    private volatile Process login;
    private String tunnelId;
    private String hostname;

    CloudflareDomain(Path dataDirectory, Path binDirectory, Logger logger, Consumer<CloudflareDomain> onReady) {
        this.directory = dataDirectory.resolve("cloudflare");
        this.home = directory.resolve("home");
        this.certificate = home.resolve(".cloudflared").resolve("cert.pem");
        this.credentials = directory.resolve("tunnel-credentials.json");
        this.stateFile = directory.resolve("tunnel.properties");
        this.binDirectory = binDirectory;
        this.logger = logger;
        this.onReady = onReady;
        load();
        state = configured() ? State.READY : State.NONE;
    }

    private void load() {
        if (!Files.exists(stateFile)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(stateFile)) {
            props.load(in);
            tunnelId = props.getProperty("tunnel-id");
            hostname = props.getProperty("hostname");
        } catch (IOException e) {
            logger.warn("Could not read {}: {}", stateFile, e.getMessage());
        }
    }

    private void save() throws IOException {
        Properties props = new Properties();
        props.setProperty("tunnel-id", tunnelId);
        props.setProperty("hostname", hostname);
        try (OutputStream out = Files.newOutputStream(stateFile)) {
            props.store(out, "The website's Cloudflare tunnel (made from the Settings page). Delete this folder to start over.");
        }
    }

    /** Whether a tunnel exists and has an address, so the website can run on it. */
    synchronized boolean configured() {
        return tunnelId != null && hostname != null && Files.exists(credentials);
    }

    synchronized String tunnelId() {
        return tunnelId;
    }

    synchronized String hostname() {
        return hostname;
    }

    Path credentials() {
        return credentials;
    }

    State state() {
        return state;
    }

    String loginUrl() {
        return state == State.WAITING ? loginUrl : null;
    }

    String message() {
        return message;
    }

    /**
     * Puts the website on {@code wanted} (e.g. dla.example.com). Logs in to Cloudflare first if needed; the link to
     * open shows up in {@link #loginUrl()} a moment later. Does nothing while a previous request is still running.
     */
    synchronized void connect(String wanted) {
        if (state == State.WAITING || state == State.WORKING) {
            return;
        }
        message = "";
        if (Files.exists(certificate)) {
            state = State.WORKING;
            background(() -> setUp(wanted));
            return;
        }
        state = State.WAITING;
        loginUrl = null;
        background(() -> logIn(wanted));
    }

    /** Step 1: cloudflared prints the authorize link, then waits until the owner has authorized in their browser. */
    private void logIn(String wanted) {
        try {
            Files.createDirectories(home);
            Process process = command(List.of("tunnel", "login"));
            login = process;
            StringBuilder output = new StringBuilder();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    output.append(line).append('\n');
                    Matcher matcher = LOGIN_URL.matcher(line);
                    if (loginUrl == null && matcher.find()) {
                        loginUrl = matcher.group();
                        logger.info("Open this link to connect the website to your Cloudflare domain: {}", loginUrl);
                    }
                }
            }
            process.waitFor();
            login = null;
            if (!Files.exists(certificate)) {
                if (state == State.WAITING) {
                    fail("The Cloudflare login wasn't finished (it expires after a few minutes). Try again.", output);
                }
                return;
            }
            state = State.WORKING;
            setUp(wanted);
        } catch (IOException | InterruptedException e) {
            fail("Couldn't run cloudflared: " + e.getMessage(), null);
        }
    }

    /** Step 2: make the tunnel (once) and point the address at it. */
    private void setUp(String wanted) {
        try {
            if (!configured()) {
                message = "Creating the tunnel…";
                Files.deleteIfExists(credentials);
                String name = "discordlinked-" + UUID.randomUUID().toString().substring(0, 8);
                Result create = run(List.of("tunnel", "--origincert", certificate.toString(), "create",
                        "--credentials-file", credentials.toString(), name));
                Matcher id = TUNNEL_ID.matcher(create.output());
                if (create.code() != 0 || !id.find() || !Files.exists(credentials)) {
                    fail("Cloudflare didn't create the tunnel.", create.output());
                    return;
                }
                synchronized (this) {
                    tunnelId = id.group(1);
                }
            }
            message = "Adding " + wanted + " to your DNS…";
            Result route = run(List.of("tunnel", "--origincert", certificate.toString(), "route", "dns", "--overwrite-dns",
                    tunnelId(), wanted));
            if (route.code() != 0) {
                fail("Cloudflare didn't add " + wanted + " to your DNS. Is that domain in the Cloudflare account you "
                        + "authorized?", route.output());
                return;
            }
            synchronized (this) {
                hostname = wanted;
                save();
            }
            message = "";
            state = State.READY;
            logger.info("The website now has its own address: https://{}", wanted);
            onReady.accept(this);
        } catch (IOException | InterruptedException e) {
            fail("Couldn't run cloudflared: " + e.getMessage(), null);
        }
    }

    /** Removes the tunnel (and with it the address) and forgets the Cloudflare login. */
    synchronized void disconnect() {
        Process running = login;
        if (running != null) {
            running.destroy();
        }
        String id = tunnelId;
        if (id != null && Files.exists(certificate)) {
            try {
                Result delete = run(List.of("tunnel", "--origincert", certificate.toString(), "delete", "-f", id));
                if (delete.code() != 0) {
                    logger.warn("Cloudflare didn't delete the tunnel {} (delete it in the Cloudflare dashboard): {}", id,
                            lastLine(delete.output()));
                }
            } catch (IOException | InterruptedException e) {
                logger.warn("Could not delete the Cloudflare tunnel: {}", e.getMessage());
            }
        }
        try {
            Files.deleteIfExists(credentials);
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(certificate);
        } catch (IOException e) {
            logger.warn("Could not remove the tunnel files in {}: {}", directory, e.getMessage());
        }
        tunnelId = null;
        hostname = null;
        loginUrl = null;
        message = "";
        state = State.NONE;
    }

    /** Called when the website couldn't run the tunnel, so the Settings page can say why. */
    void runFailed(String detail) {
        if (state == State.READY) {
            message = "The tunnel didn't connect, so the website is on a temporary address for now"
                    + (detail == null || detail.isBlank() ? "." : ": " + lastLine(detail));
        }
    }

    private void fail(String why, CharSequence output) {
        String detail = output == null ? "" : lastLine(output.toString());
        message = detail.isEmpty() ? why : why + " (" + detail + ")";
        state = configured() ? State.READY : State.ERROR;
        logger.warn("Own-domain setup: {}", message);
    }

    static String lastLine(String output) {
        String[] lines = output.strip().split("\n");
        String last = lines[lines.length - 1].strip();
        // cloudflared log lines start with a timestamp and level; keep the message.
        return last.replaceFirst("^\\S+\\s+(INF|WRN|ERR|DBG)\\s+", "");
    }

    private record Result(int code, String output) {
    }

    private Result run(List<String> args) throws IOException, InterruptedException {
        Process process = command(args);
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return new Result(-1, output + "\ntimed out");
        }
        return new Result(process.exitValue(), output);
    }

    /** Runs cloudflared with its home folder inside ours, so the login certificate lands there. */
    private Process command(List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(CloudflareTunnel.binary(binDirectory, logger).toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("USERPROFILE", home.toString());
        return builder.start();
    }

    private static void background(Runnable task) {
        Thread thread = new Thread(task, "DiscordLinked-Cloudflare");
        thread.setDaemon(true);
        thread.start();
    }
}
