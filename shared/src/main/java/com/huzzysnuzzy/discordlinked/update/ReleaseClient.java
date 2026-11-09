package com.huzzysnuzzy.discordlinked.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads the newest release of DiscordLinked from GitHub and downloads its jars.
 *
 * <p>This never calls GitHub's API (api.github.com, 60 requests an hour per IP address without a token). Each release
 * has a small {@code update.properties} file (written by the repository's Build workflow) with the version and the
 * jars' names, sizes and SHA-256 checksums, and it's fetched like any download from a release page:
 * {@code https://github.com/<owner>/<repo>/releases/latest/download/update.properties}. "latest" is the newest
 * release that isn't a draft or pre-release. The jars come from the same release, and a jar whose size or checksum
 * doesn't match is thrown away.
 *
 * <p>Where it downloads from can be changed with {@code -Ddiscordlinked.update.url=...} (instead of
 * https://github.com), for testing.
 */
public final class ReleaseClient {

    public static final String URL_PROPERTY = "discordlinked.update.url";
    public static final String MANIFEST = "update.properties";
    /** The repository updates come from unless the config says otherwise. */
    public static final String DEFAULT_REPOSITORY = "HuzzySnuzzy/DiscordLinked";
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,100}\\.jar");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final long MAX_JAR_BYTES = 200L << 20;
    private static final int MAX_MANIFEST_BYTES = 16 << 10;

    /** One jar of a release. */
    public record Asset(String file, String sha256, long size) {
    }

    /** A release: its version, its tag (where its files are) and the two jars. */
    public record Release(String version, String tag, Asset proxy, Asset backend) {
    }

    /** GitHub asked to wait (429, or 403 with a rate limit). {@code retryAt} is when, in epoch millis (0 = not said). */
    public static final class RateLimited extends IOException {
        public final long retryAt;

        RateLimited(String message, long retryAt) {
            super(message);
            this.retryAt = retryAt;
        }
    }

    /** The repository has no release with an update.properties (yet). */
    public static final class NoRelease extends IOException {
        NoRelease(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public ReleaseClient(String userAgent) {
        this.userAgent = userAgent;
    }

    public static boolean validRepository(String repository) {
        return repository != null && REPOSITORY.matcher(repository).matches();
    }

    private static String base() {
        String base = System.getProperty(URL_PROPERTY, "https://github.com").strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The release's page, for its notes. Always on github.com. */
    public static String page(String repository, String tag) {
        return "https://github.com/" + repository + "/releases/tag/" + tag;
    }

    /** The newest release (one request, a few hundred bytes). */
    public Release latest(String repository) throws IOException, InterruptedException {
        if (!validRepository(repository)) {
            throw new IOException("\"" + repository + "\" isn't a GitHub repository (owner/name)");
        }
        HttpRequest request = request(base() + "/" + repository + "/releases/latest/download/" + MANIFEST)
                .timeout(Duration.ofSeconds(30)).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            check(response.statusCode(), response.headers(), repository, true);
            byte[] bytes = body.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) {
                throw new IOException("The release's " + MANIFEST + " is too big");
            }
            Properties props = new Properties();
            try {
                props.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException e) {
                // A malformed escape sequence: a page that isn't the release's file at all.
                throw new IOException("The release's " + MANIFEST + " is unreadable: " + e.getMessage());
            }
            return parse(props);
        }
    }

    static Release parse(Properties props) throws IOException {
        String version = props.getProperty("version", "").strip();
        String tag = props.getProperty("tag", "").strip();
        if (!Version.valid(version) || !TAG.matcher(tag).matches()) {
            throw new IOException("The release's " + MANIFEST + " has no valid version or tag");
        }
        return new Release(version, tag, asset(props, "proxy"), asset(props, "backend"));
    }

    private static Asset asset(Properties props, String which) throws IOException {
        String file = props.getProperty(which + ".file", "").strip();
        String sha256 = props.getProperty(which + ".sha256", "").strip().toLowerCase(Locale.ROOT);
        long size;
        try {
            size = Long.parseLong(props.getProperty(which + ".size", "").strip());
        } catch (NumberFormatException e) {
            size = -1;
        }
        if (!FILE.matcher(file).matches() || !SHA256.matcher(sha256).matches() || size <= 0 || size > MAX_JAR_BYTES) {
            throw new IOException("The release's " + MANIFEST + " has no valid " + which + " jar");
        }
        return new Asset(file, sha256, size);
    }

    /**
     * Downloads one jar of the release to {@code target} (replacing it), checking its size and SHA-256 on the way.
     * Nothing is left at {@code target} if it fails.
     */
    public void download(String repository, Release release, Asset asset, Path target)
            throws IOException, InterruptedException {
        HttpRequest request = request(base() + "/" + repository + "/releases/download/" + release.tag() + "/"
                + asset.file()).timeout(Duration.ofMinutes(10)).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        boolean done = false;
        try (InputStream body = response.body()) {
            check(response.statusCode(), response.headers(), repository, false);
            long written = 0;
            try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 << 10];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    written += read;
                    if (written > asset.size()) {
                        throw new IOException(asset.file() + " is bigger than the release says");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (written != asset.size()) {
                throw new IOException(asset.file() + " was cut short (" + written + " of " + asset.size() + " bytes)");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (!sha256.equals(asset.sha256())) {
                throw new IOException(asset.file() + " doesn't match the release's checksum");
            }
            done = true;
        } finally {
            if (!done) {
                Files.deleteIfExists(target);
            }
        }
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).GET();
    }

    private static void check(int status, HttpHeaders headers, String repository, boolean manifest)
            throws IOException {
        if (status == 200) {
            return;
        }
        if (status == 404) {
            // No release at all is normal for a new repository; a release without its jar is a broken release.
            if (manifest) {
                throw new NoRelease("No release with " + MANIFEST + " in github.com/" + repository + " yet");
            }
            throw new IOException("The release's jar isn't there (HTTP 404)");
        }
        boolean limited = status == 429 || (status == 403 && (headers.firstValue("retry-after").isPresent()
                || headers.firstValue("x-ratelimit-remaining").map("0"::equals).orElse(false)));
        if (limited) {
            throw new RateLimited("GitHub asked to wait (HTTP " + status + ")", retryAt(headers));
        }
        throw new IOException("GitHub answered HTTP " + status);
    }

    /** When GitHub said to try again: Retry-After (seconds or a date), else X-RateLimit-Reset (epoch seconds). */
    static long retryAt(HttpHeaders headers) {
        long now = System.currentTimeMillis();
        Optional<String> after = headers.firstValue("retry-after").map(String::strip);
        if (after.isPresent()) {
            try {
                return now + Math.max(0, Long.parseLong(after.get())) * 1000;
            } catch (NumberFormatException e) {
                try {
                    return ZonedDateTime.parse(after.get(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }
        }
        Optional<String> reset = headers.firstValue("x-ratelimit-reset").map(String::strip);
        if (reset.isPresent()) {
            try {
                return Long.parseLong(reset.get()) * 1000;
            } catch (NumberFormatException ignored) {
                // not said
            }
        }
        return 0;
    }
}
