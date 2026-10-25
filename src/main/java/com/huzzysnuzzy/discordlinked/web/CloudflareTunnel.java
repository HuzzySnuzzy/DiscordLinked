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
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Gives the website a public https:// address with zero setup, using a Cloudflare "quick tunnel":
 * downloads Cloudflare's cloudflared program on first use and runs {@code cloudflared tunnel --url}.
 * No account, domain or open ports are needed, but the address changes every time it starts.
 */
final class CloudflareTunnel {

    private static final Pattern URL = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final String RELEASES = "https://github.com/cloudflare/cloudflared/releases/latest/download/";

    private final Path binDirectory;
    private final int localPort;
    private final Logger logger;
    private final Consumer<String> onUrl;
    private volatile Process process;
    private volatile boolean stopping;
    private Thread supervisor;

    CloudflareTunnel(Path binDirectory, int localPort, Logger logger, Consumer<String> onUrl) {
        this.binDirectory = binDirectory;
        this.localPort = localPort;
        this.logger = logger;
        this.onUrl = onUrl;
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
            binary = ensureBinary();
        } catch (IOException | InterruptedException | UnsupportedOperationException e) {
            logger.error("Could not set up HTTPS for the admin website: {}. Set web-access=direct in "
                    + "config.properties to serve it over plain http on web-port instead.", e.getMessage());
            return;
        }
        long backoffSeconds = 5;
        while (!stopping) {
            long started = System.currentTimeMillis();
            try {
                process = new ProcessBuilder(binary.toString(), "tunnel", "--no-autoupdate",
                        "--url", "http://127.0.0.1:" + localPort)
                        .redirectErrorStream(true)
                        .start();
                boolean announced = false;
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        Matcher matcher = URL.matcher(line);
                        if (!announced && matcher.find()) {
                            announced = true;
                            onUrl.accept(matcher.group());
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

    private Path ensureBinary() throws IOException, InterruptedException {
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
