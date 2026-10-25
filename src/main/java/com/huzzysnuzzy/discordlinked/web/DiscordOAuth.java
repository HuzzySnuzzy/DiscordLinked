package com.huzzysnuzzy.discordlinked.web;

import com.huzzysnuzzy.discordlinked.DiscordLinked;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** The "Log in with Discord" (OAuth2 authorization code) flow. Only the {@code identify} scope is used. */
final class DiscordOAuth {

    private static final String API = "https://discord.com/api/v10";
    private static final String USER_AGENT =
            "DiscordBot (https://github.com/HuzzySnuzzy/DiscordLinked, " + DiscordLinked.VERSION + ")";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String clientSecret;
    private final String redirectUri;

    DiscordOAuth(String clientSecret, String redirectUri) {
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    String authorizeUrl(String clientId, String state) {
        return "https://discord.com/oauth2/authorize?" + form(Map.of(
                "client_id", clientId,
                "response_type", "code",
                "scope", "identify",
                "redirect_uri", redirectUri,
                "state", state,
                "prompt", "none"));
    }

    /** Exchanges the code from the callback for the user's identity. */
    DiscordUser identify(String clientId, String code) throws IOException, InterruptedException {
        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(API + "/oauth2/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "client_id", clientId,
                        "client_secret", clientSecret,
                        "grant_type", "authorization_code",
                        "code", code,
                        "redirect_uri", redirectUri))))
                .build();
        DataObject token = send(tokenRequest);

        HttpRequest userRequest = HttpRequest.newBuilder(URI.create(API + "/users/@me"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token.getString("access_token"))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        DataObject user = send(userRequest);

        long id = Long.parseLong(user.getString("id"));
        String name = user.getString("global_name", null);
        if (name == null || name.isBlank()) {
            name = user.getString("username");
        }
        String avatarHash = user.getString("avatar", null);
        return new DiscordUser(id, name, avatarUrl(id, avatarHash));
    }

    static String avatarUrl(long userId, String avatarHash) {
        if (avatarHash == null) {
            return "https://cdn.discordapp.com/embed/avatars/" + ((userId >> 22) % 6) + ".png";
        }
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatarHash + ".png?size=64";
    }

    private DataObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Discord returned " + response.statusCode() + " for " + request.uri().getPath()
                    + ": " + response.body());
        }
        return DataObject.fromJson(response.body());
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    record DiscordUser(long id, String name, String avatarUrl) {
        String actor() {
            return name + " (" + id + ")";
        }
    }
}
