package com.ainative.integration.generic;

import com.ainative.json.JSONObject;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class GenericHttpWorkitemWritebackProvider {

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    public GenericHttpWorkitemWritebackProvider() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build());
    }

    GenericHttpWorkitemWritebackProvider(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void updateContent(String provider, ExternalPlatformConfig config, String externalWorkitemId,
                              String title, String contentMd) {
        if (isBlank(config.baseUrl())) {
            throw new ExternalPlatformException("Generic writeback baseUrl is required");
        }
        if (isBlank(externalWorkitemId)) {
            throw new ExternalPlatformException("Generic writeback externalWorkitemId is required");
        }
        JSONObject payload = new JSONObject();
        payload.put("provider", provider);
        payload.put("externalWorkitemId", externalWorkitemId);
        payload.put("title", title);
        payload.put("contentMd", contentMd);

        Request.Builder builder = new Request.Builder()
                .url(trimTrailingSlash(config.baseUrl()) + "/api/workitems/"
                        + pathSegment(externalWorkitemId) + "/content")
                .put(RequestBody.create(payload.toJSONString(), JSON_MEDIA))
                .addHeader("X-AINative-Provider", provider == null ? "" : provider);
        if (!isBlank(config.clientKey())) {
            builder.addHeader("X-AINative-Client-Key", config.clientKey());
        }
        if (!isBlank(config.accessSecret())) {
            builder.addHeader("Authorization", "Bearer " + config.accessSecret());
        }
        execute(builder.build());
    }

    private void execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return;
            }
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            throw new ExternalPlatformException("Generic writeback failed: HTTP " + response.code()
                    + (text.isBlank() ? "" : " " + text));
        } catch (IOException e) {
            throw new ExternalPlatformException("Generic writeback request failed: " + e.getMessage(), e);
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
