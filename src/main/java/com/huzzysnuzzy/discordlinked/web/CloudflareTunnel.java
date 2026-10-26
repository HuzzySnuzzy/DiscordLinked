package com.huzzysnuzzy.discordlinked.web;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Gives the website a public https:// address by running Cloudflare's cloudflared program (downloaded on first
 * use) and keeping it running. Three ways:
 * <ul>
 *   <li>a "quick tunnel": no account or domain needed, but the address changes every time it starts;</li>
 *   <li>a named tunnel on the owner's own domain, set up from the Settings page (see {@link CloudflareDomain});</li>
 *   <li>a tunnel made in Cloudflare's dashboard, run with its token.</li>
 * </ul>
 */
final class CloudflareTunnel {

    /**
     * How to run it: cloudflared's arguments, a pattern for the output line that means it's live, and the address
     * (null: the address is the matched text, as with quick tunnels).
     */
    record Mode(String name, List<String> args, Pattern live, String url) {
    }

    /** cloudflared prints this once a named or token tunnel is connected to Cloudflare. */
    private static final Pattern REGISTERED = Pattern.compile("Registered tunnel connection|Connection [0-9a-f-]+ registered");

    static Mode quick(int localPort) {
        return new Mode("quick", List.of("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:" + localPort), URL, null);
    }

    static Mode named(Path credentials, String tunnelId, int localPort, String url) {
        return new Mode("domain", List.of("tunnel", "--no-autoupdate", "run", "--credentials-file", credentials.toString(),
                "--url", "http://127.0.0.1:" + localPort, tunnelId), REGISTERED, url);
    }

    static Mode token(String token, String url) {
        return new Mode("token", List.of("tunnel", "--no-autoupdate", "run", "--token", token), REGISTERED, url);
    }

    /**
     * A quick tunnel address is random words joined by hyphens. cloudflared's own error messages mention
     * https://api.trycloudflare.com, which must not be mistaken for the website's address.
     */
    private static final Pattern URL = Pattern.compile("https://(?!api\\.)[a-z0-9]+(?:-[a-z0-9]+)+\\.trycloudflare\\.com");
    private static final String RELEASES = "https://github.com/cloudflare/cloudflared/releases/latest/download/";

    /** A named or token tunnel that never connects gives up after this many tries, so the website can fall back. */
    private static final int TRIES_BEFORE_GIVING_UP = 3;

    private final Path binDirectory;
    private final Mode mode;
    private final Logger logger;
    private final Consumer<String> onUrl;
    private final Consumer<String> onGiveUp;
    private volatile Process process;
    private volatile boolean stopping;
    private Thread supervisor;

    /** {@code onGiveUp} (named and token tunnels only) gets the last output line when it can't connect. */
    CloudflareTunnel(Path binDirectory, Mode mode, Logger logger, Consumer<String> onUrl, Consumer<String> onGiveUp) {
        this.binDirectory = binDirectory;
        this.mode = mode;
        this.logger = logger;
        this.onUrl = onUrl;
        this.onGiveUp = onGiveUp;
    }

    Mode mode() {
        return mode;
    }

    /** Starts in the background; {@code onUrl} is called with each new address. */
    void start() {
        supervisor = new Thread(this::run, "DiscordLinked-Tunnel");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    void stop() {
        stopping = true;
        Process running = process;
        if (running != null) {
            running.destroy();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }
    }

    private void run() {
        Path binary;
        try {
            binary = binary(binDirectory, logger);
        } catch (IOException | InterruptedException | UnsupportedOperationException e) {
            logger.error("Could not set up HTTPS for the admin website: {}. Set web-access=direct in "
                    + "config.properties to serve it over plain http on web-port instead.", e.getMessage());
            if (onGiveUp != null) {
                onGiveUp.accept(e.getMessage());
            }
            return;
        }
        long backoffSeconds = 5;
        boolean everConnected = false;
        int failedTries = 0;
        while (!stopping) {
            long started = System.currentTimeMillis();
            String lastLine = "";
            try {
                List<String> command = new ArrayList<>();
                command.add(binary.toString());
                command.addAll(mode.args());
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
                boolean announced = false;
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        if (!line.isBlank()) {
                            lastLine = line;
                        }
                        Matcher matcher = mode.live().matcher(line);
                        if (!announced && matcher.find()) {
                            announced = true;
                            everConnected = true;
                            onUrl.accept(mode.url() != null ? mode.url() : matcher.group());
                        }
                    }
                }
                int code = process.waitFor();
                if (!stopping) {
                    logger.warn("The HTTPS tunnel stopped (exit code {}); restarting it in {}s", code, backoffSeconds);
                }
            } catch (IOException e) {
                logger.warn("Could not run cloudflared ({}); retrying in {}s. If your host doesn't allow running "
                        + "programs, set web-access=direct.", e.getMessage(), backoffSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopping) {
                return;
            }
            if (onGiveUp != null && !everConnected && ++failedTries >= TRIES_BEFORE_GIVING_UP) {
                logger.warn("The {} tunnel couldn't connect ({}).", mode.name(), lastLine);
                onGiveUp.accept(lastLine);
                return;
            }
            // Ran fine for a while: reset the delay. Keeps failing: back off up to 5 minutes.
            if (System.currentTimeMillis() - started > 60_000) {
                backoffSeconds = 5;
            }
            try {
                Thread.sleep(backoffSeconds * 1000);
            } catch (InterruptedException e) {
                return;
            }
            backoffSeconds = Math.min(300, backoffSeconds * 2);
        }
    }

    /** The cloudflared program for this machine, downloaded into {@code binDirectory} the first time. */
    static synchronized Path binary(Path binDirectory, Logger logger) throws IOException, InterruptedException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String cpu = switch (arch) {
            case "amd64", "x86_64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case "arm", "armv7l" -> "arm";
            case "x86", "i386", "i686" -> "386";
            default -> throw new UnsupportedOperationException("unsupported CPU " + arch);
        };
        String asset;
        boolean archive = false;
        Path binary;
        if (os.contains("win")) {
            asset = "cloudflared-windows-" + cpu + ".exe";
            binary = binDirectory.resolve("cloudflared.exe");
        } else if (os.contains("mac") || os.contains("darwin")) {
            asset = "cloudflared-darwin-" + cpu + ".tgz";
            archive = true;
            binary = binDirectory.resolve("cloudflared");
        } else if (os.contains("linux")) {
            asset = "cloudflared-linux-" + cpu;
            binary = binDirectory.resolve("cloudflared");
        } else {
            throw new UnsupportedOperationException("unsupported OS " + os);
        }
        if (Files.isExecutable(binary)) {
            return binary;
        }
        Files.createDirectories(binDirectory);
        logger.info("Downloading Cloudflare's cloudflared ({}) for the admin website's HTTPS address...", asset);
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(RELEASES + asset))
                .timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("download failed with HTTP " + response.statusCode());
        }
        Path partial = binDirectory.resolve(binary.getFileName() + ".download");
        try (InputStream in = response.body()) {
            if (archive) {
                extractFromTarGz(in, "cloudflared", partial);
            } else {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(partial, binary, StandardCopyOption.REPLACE_EXISTING);
        if (!binary.toFile().setExecutable(true)) {
            throw new IOException("couldn't make " + binary + " executable");
        }
        return binary;
    }

    /** Minimal tar reader: copies the entry named {@code name} out of a .tar.gz stream. */
    private static void extractFromTarGz(InputStream raw, String name, Path target) throws IOException {
        try (InputStream in = new GZIPInputStream(raw)) {
            byte[] header = new byte[512];
            while (in.readNBytes(header, 0, 512) == 512) {
                String entry = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
                if (entry.isEmpty()) {
                    break;
                }
                long size = Long.parseLong(new String(header, 124, 11, StandardCharsets.US_ASCII).trim(), 8);
                long padded = (size + 511) / 512 * 512;
                if (entry.equals(name) || entry.endsWith("/" + name)) {
                    try (OutputStream out = Files.newOutputStream(target)) {
                        long left = size;
                        byte[] buffer = new byte[8192];
                        while (left > 0) {
                            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                            if (read < 0) {
                                throw new IOException("truncated archive");
                            }
                            out.write(buffer, 0, read);
                            left -= read;
                        }
                    }
                    return;
                }
                in.skipNBytes(padded);
            }
        }
        throw new IOException(name + " not found in the download");
    }
}
