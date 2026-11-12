package com.ainative.v2.auth;

import com.ainative.v2.common.BizException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 身份域服务:注册/登录/当前用户。契约与 v1 /api/auth/* 对齐。 */
@Service
public class AuthService {
    private final UserDao users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserDao users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public Map<String, Object> register(String username, String password, String nickname, String email) {
        if (username == null || username.isBlank()) {
            throw new BizException("10001", "用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new BizException("10001", "密码至少 6 位");
        }
        if (users.findByUsername(username.trim()).isPresent()) {
            throw new BizException("10002", "用户名已存在");
        }
        long id = users.insert(username.trim(), nickname == null ? username.trim() : nickname,
                email, encoder.encode(password));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("username", username.trim());
        return out;
    }

    public Map<String, Object> login(String username, String password) {
        return login(username, password, null);
    }

    public Map<String, Object> login(String username, String password, Long workspaceId) {
        UserDao.User user = users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new BizException("10401", "用户名或密码错误"));
        if (!"ACTIVE".equals(user.status()) || !encoder.matches(password == null ? "" : password, user.passwordHash())) {
            throw new BizException("10401", "用户名或密码错误");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", jwt.issue(user.id(), workspaceId));
        if (workspaceId != null) {
            out.put("workspace", workspaceId);
        }
        out.put("username", user.username());
        out.put("role", user.admin() ? "admin" : "member");
        return out;
    }

    public Map<String, Object> me(Long uid) {
        UserDao.User user = users.findById(uid)
                .orElseThrow(() -> new BizException("10401", "未登录或登录已失效"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("username", user.username());
        out.put("nickname", user.nickname());
        out.put("email", user.email());
        out.put("isAdmin", user.admin());
        return out;
    }
}
