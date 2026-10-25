package com.huzzysnuzzy.discordlinked.web;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Writes rows into a new tab of a Google spreadsheet using a service account.
 * The spreadsheet must be shared with the service account's email as an Editor.
 */
final class GoogleSheetsExporter {

    private static final String SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String SHEETS_API = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final int ROWS_PER_REQUEST = 5_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String spreadsheetId;
    private final String clientEmail;
    private final String tokenUri;
    private final String keyId;
    private final PrivateKey privateKey;
    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    private GoogleSheetsExporter(String spreadsheetId, DataObject key) throws GeneralSecurityException {
        this.spreadsheetId = spreadsheetId;
        this.clientEmail = key.getString("client_email");
        this.tokenUri = key.getString("token_uri", "https://oauth2.googleapis.com/token");
        this.keyId = key.getString("private_key_id", "");
        String pem = key.getString("private_key")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        this.privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    static GoogleSheetsExporter load(String spreadsheetId, Path keyFile) throws IOException {
        try {
            DataObject key = DataObject.fromJson(Files.readString(keyFile));
            if (!key.hasKey("client_email") || !key.hasKey("private_key")) {
                throw new IOException(keyFile.getFileName() + " is not a service account key (no client_email/private_key)");
            }
            return new GoogleSheetsExporter(spreadsheetId, key);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Could not read the private key in " + keyFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    String clientEmail() {
        return clientEmail;
    }

    /** Adds a tab called {@code title}, writes {@code header} + {@code rows}, and returns a link to it. */
    synchronized String export(String title, List<String> header, List<List<String>> rows)
            throws IOException, InterruptedException {
        DataObject addSheet = DataObject.empty().put("requests", DataArray.empty().add(DataObject.empty()
                .put("addSheet", DataObject.empty().put("properties", DataObject.empty()
                        .put("title", title)
                        .put("gridProperties", DataObject.empty()
                                .put("rowCount", rows.size() + 1)
                                .put("columnCount", header.size())
                                .put("frozenRowCount", 1))))));
        DataObject reply = call("POST", SHEETS_API + spreadsheetId + ":batchUpdate", addSheet);
        long sheetId = reply.getArray("replies").getObject(0)
                .getObject("addSheet").getObject("properties").getLong("sheetId");

        writeRows(title, 1, List.of(header));
        for (int start = 0; start < rows.size(); start += ROWS_PER_REQUEST) {
            writeRows(title, start + 2, rows.subList(start, Math.min(rows.size(), start + ROWS_PER_REQUEST)));
        }

        DataObject format = DataObject.empty().put("requests", DataArray.empty()
                .add(DataObject.empty().put("repeatCell", DataObject.empty()
                        .put("range", DataObject.empty().put("sheetId", sheetId).put("startRowIndex", 0).put("endRowIndex", 1))
                        .put("cell", DataObject.empty().put("userEnteredFormat", DataObject.empty()
                                .put("textFormat", DataObject.empty().put("bold", true))))
                        .put("fields", "userEnteredFormat.textFormat.bold")))
                .add(DataObject.empty().put("autoResizeDimensions", DataObject.empty()
                        .put("dimensions", DataObject.empty().put("sheetId", sheetId).put("dimension", "COLUMNS")
                                .put("startIndex", 0).put("endIndex", header.size())))));
        call("POST", SHEETS_API + spreadsheetId + ":batchUpdate", format);

        return "https://docs.google.com/spreadsheets/d/" + spreadsheetId + "/edit#gid=" + sheetId;
    }

    private void writeRows(String title, int firstRow, List<List<String>> rows) throws IOException, InterruptedException {
        DataArray values = DataArray.empty();
        for (List<String> row : rows) {
            values.add(DataArray.fromCollection(row));
        }
        String range = "'" + title + "'!A" + firstRow;
        // RAW stores everything as plain text, so chat like "=IMPORTXML(...)" is never run as a formula.
        call("PUT", SHEETS_API + spreadsheetId + "/values/" + encodePath(range) + "?valueInputOption=RAW",
                DataObject.empty().put("range", range).put("majorDimension", "ROWS").put("values", values));
    }

    private DataObject call(String method, String url, DataObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(explain(response.statusCode(), response.body()));
        }
        return DataObject.fromJson(response.body());
    }

    private String explain(int status, String body) {
        if (body.contains("SERVICE_DISABLED") || body.contains("has not been used in project")) {
            return "The Google Sheets API isn't enabled for the service account's Google Cloud project. "
                    + "Enable it in the Google Cloud console (APIs & Services -> Library -> Google Sheets API).";
        }
        if (status == 403) {
            return "Google refused access. Share the spreadsheet with " + clientEmail + " as an Editor.";
        }
        if (status == 404) {
            return "Spreadsheet not found. Check google-sheets-spreadsheet-id in config.properties.";
        }
        String message = body;
        try {
            message = DataObject.fromJson(body).getObject("error").getString("message", body);
        } catch (RuntimeException ignored) {
            // not the usual error JSON
        }
        return "Google Sheets returned " + status + ": " + message;
    }

    private String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpires)) {
            return accessToken;
        }
        long now = Instant.now().getEpochSecond();
        String header = base64Url(DataObject.empty().put("alg", "RS256").put("typ", "JWT").put("kid", keyId).toString());
        String claims = base64Url(DataObject.empty()
                .put("iss", clientEmail).put("scope", SCOPE).put("aud", tokenUri)
                .put("iat", now).put("exp", now + 3600).toString());
        String unsigned = header + "." + claims;
        String jwt;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign the Google login token: " + e.getMessage(), e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                                + "&assertion=" + jwt))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google rejected the service account login (" + response.statusCode() + "): "
                    + response.body() + ". Check that the key file is current and not deleted in Google Cloud.");
        }
        DataObject token = DataObject.fromJson(response.body());
        accessToken = token.getString("access_token");
        accessTokenExpires = Instant.now().plusSeconds(token.getLong("expires_in", 3600) - 60);
        return accessToken;
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
