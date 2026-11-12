package com.ainative.v2.auth;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record RegisterRequest(String username, String password, String nickname, String email) {}
    public record LoginRequest(String username, String password, Long workspaceId) {}

    private final AuthService auth;
    private final JwtService jwt;

    public AuthController(AuthService auth, JwtService jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    @PostMapping("/register")
    public ApiResult<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        return ApiResult.ok(auth.register(req.username(), req.password(), req.nickname(), req.email()));
    }

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody LoginRequest req) {
        return ApiResult.ok(auth.login(req.username(), req.password(), req.workspaceId()));
    }

    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        Long uid = jwt.verify(bearer(authorization));
        if (uid == null) {
            throw new BizException("10401", "未登录或登录已失效");
        }
        return ApiResult.ok(auth.me(uid));
    }

    private static String bearer(String header) {
        if (header == null) {
            return null;
        }
        return header.startsWith("Bearer ") ? header.substring(7) : header;
    }
}
