package com.ainative.integration.zentao;

import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 禅道 v1 REST API 客户端(实测于 ZenTao 18.10)。
 *
 * 认证:POST /api.php/v1/tokens {account,password} → {token};后续请求头 Token: &lt;token&gt;。
 * token 有效期上游未在响应中声明,按 25 分钟保守缓存;遇 401 强制刷新重试一次。
 * 凭证只经方法参数/内存传递,日志脱敏。
 */
@Component
public class ZentaoClient {

    private static final Logger log = LoggerFactory.getLogger(ZentaoClient.class);
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final long TOKEN_TTL_MILLIS = 25 * 60 * 1000L;

    private final OkHttpClient httpClient;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public ZentaoClient() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build());
    }

    protected ZentaoClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private record CachedToken(String token, long fetchedAtMillis) { }

    private static String cacheKey(String baseUrl, String account) {
        return baseUrl + "|" + account;
    }

    /** 拉取(或复用缓存)禅道 API token。401 时调用 {@link #invalidate(String, String)} 后重试。 */
    public String token(String baseUrl, String account, String password) {
        String key = cacheKey(baseUrl, account);
        CachedToken cached = tokenCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMillis() < TOKEN_TTL_MILLIS) {
            return cached.token();
        }
        return refreshToken(baseUrl, account, password);
    }

    public String refreshToken(String baseUrl, String account, String password) {
        JSONObject body = new JSONObject();
        body.put("account", account);
        body.put("password", password);
        JSONObject resp = execute("POST", baseUrl, account, password, "/api.php/v1/tokens", body, true);
        String token = resp.getString("token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("zentao token response missing token");
        }
        tokenCache.put(cacheKey(baseUrl, account), new CachedToken(token, System.currentTimeMillis()));
        log.info("zentao token refreshed baseUrl={} account={}", baseUrl, account);
        return token;
    }

    public void invalidate(String baseUrl, String account) {
        tokenCache.remove(cacheKey(baseUrl, account));
    }

    /** 产品列表(绑定表单下拉用)。返回原始 JSON 数组页。 */
    public com.ainative.json.JSONArray listProducts(String baseUrl, String account, String password) {
        JSONObject resp = getJson(baseUrl, account, password, "/api.php/v1/products?page=1&limit=100");
        com.ainative.json.JSONArray products = resp.getJSONArray("products");
        return products == null ? new com.ainative.json.JSONArray() : products;
    }

    public JSONObject getJson(String baseUrl, String account, String password, String path) {
        return execute("GET", baseUrl, account, password, path, null, false);
    }

    public JSONObject putJson(String baseUrl, String account, String password, String path, JSONObject body) {
        return execute("PUT", baseUrl, account, password, path, body, false);
    }

    /** 执行一次请求;401 时刷新 token 重试一次。返回禅道 JSON 响应。 */
    private JSONObject execute(String method, String baseUrl, String account, String password,
                               String path, JSONObject body, boolean tokenRefreshed) {
        // tokens 端点本身不需要(也不能再取)token:execute→token→refreshToken→execute 会死循环
        boolean isTokenEndpoint = "/api.php/v1/tokens".equals(path);
        String token = isTokenEndpoint ? null : token(baseUrl, account, password);
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .header("Content-Type", "application/json");
        if (!"/api.php/v1/tokens".equals(path)) {
            builder.header("Token", token);
        }
        Request request;
        if (body != null) {
            RequestBody payload = RequestBody.create(body.toJSONString(), JSON_MEDIA);
            request = "POST".equals(method) ? builder.post(payload).build() : builder.put(payload).build();
        } else {
            request = builder.get().build();
        }
        try (Response response = httpClient.newCall(request).execute()) {
            String text = body(response);
            if (response.code() == 401 && !tokenRefreshed) {
                invalidate(baseUrl, account);
                return execute(method, baseUrl, account, password, path, body, true);
            }
            if (response.code() == 401) {
                throw new IllegalStateException("zentao unauthorized after token refresh: " + path);
            }
            if (response.code() == 404) {
                throw new ZentaoNotFoundException(path);
            }
            if (response.code() >= 400) {
                throw new IllegalStateException("zentao request failed " + response.code() + " " + path
                        + ": " + firstError(text));
            }
            return JSON.parseObject(text);
        } catch (IOException e) {
            throw new IllegalStateException("zentao io error " + path + ": " + e.getMessage(), e);
        }
    }

    /** 禅道 PUT 涉及状态机非法转换时返回 4xx;此方法把 404 与其它错误区分开供调用方翻译。 */
    public static class ZentaoNotFoundException extends RuntimeException {
        public ZentaoNotFoundException(String path) {
            super("zentao resource not found: " + path);
        }
    }

    private static String firstError(String text) {
        try {
            JSONObject json = JSON.parseObject(text);
            String error = json.getString("error");
            return error == null ? text : error;
        } catch (RuntimeException parseFailure) {
            return text;
        }
    }

    private static String body(Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
            return responseBody == null ? "{}" : responseBody.string();
        }
    }
}
