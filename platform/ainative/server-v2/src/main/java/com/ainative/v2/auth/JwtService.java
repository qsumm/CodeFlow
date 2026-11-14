package com.ainative.v2.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** HS512 JWT(零第三方依赖):与 v1 令牌格式兼容(header.payload.sig,sub/uid/iat/exp/workspace)。 */
@Service
public class JwtService {
    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtService(@Value("${ainative.jwt-secret}") String secret,
                      @Value("${ainative.jwt-ttl-seconds:7200}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
    }

    private static final long REFRESH_TTL_SECONDS = 30L * 24 * 3600;

    public String issue(long userId, Long workspaceId) {
        return issue(userId, workspaceId, ttlSeconds, "access");
    }

    /** 刷新令牌:有效期 30 天,typ=refresh;不能直接当访问令牌用(verifyClaims 会拒)。 */
    public String issueRefresh(long userId, Long workspaceId) {
        return issue(userId, workspaceId, REFRESH_TTL_SECONDS, "refresh");
    }

    private String issue(long userId, Long workspaceId, long ttl, String typ) {
        try {
            Map<String, Object> header = Map.of("alg", "HS512");
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", String.valueOf(userId));
            claims.put("uid", userId);
            claims.put("typ", typ);
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().getEpochSecond() + ttl);
            if (workspaceId != null) {
                claims.put("workspace", workspaceId);
            }
            String h = b64(mapper.writeValueAsBytes(header));
            String p = b64(mapper.writeValueAsBytes(claims));
            return h + "." + p + "." + b64(sign(h + "." + p));
        } catch (Exception e) {
            throw new IllegalStateException("token issue failed", e);
        }
    }

    public record TokenClaims(Long userId, Long workspaceId) {}

    /** 验签并返回 uid;失败返回 null。 */
    public Long verify(String token) {
        TokenClaims claims = verifyClaims(token);
        return claims == null ? null : claims.userId();
    }

    /** 验签并返回 uid + workspace 声明;失败返回 null。 */
    public TokenClaims verifyClaims(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            Map<?, ?> claims = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            Object exp = claims.get("exp");
            if (exp instanceof Number n && n.longValue() < Instant.now().getEpochSecond()) {
                return null;
            }
            Object uid = claims.get("uid");
            if (!(uid instanceof Number n)) {
                return null;
            }
            // 刷新令牌不得冒充访问令牌
            if ("refresh".equals(String.valueOf(claims.get("typ")))) {
                return null;
            }
            Object ws = claims.get("workspace");
            return new TokenClaims(n.longValue(), ws instanceof Number w ? w.longValue() : null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 校验刷新令牌;访问令牌或伪造令牌返回 null。 */
    public TokenClaims verifyRefresh(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            Map<?, ?> claims = mapper.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            if (!"refresh".equals(String.valueOf(claims.get("typ")))) {
                return null;
            }
            Object exp = claims.get("exp");
            if (exp instanceof Number n && n.longValue() < Instant.now().getEpochSecond()) {
                return null;
            }
            Object uid = claims.get("uid");
            if (!(uid instanceof Number n)) {
                return null;
            }
            Object ws = claims.get("workspace");
            return new TokenClaims(n.longValue(), ws instanceof Number w ? w.longValue() : null);
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret, "HmacSHA512"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
