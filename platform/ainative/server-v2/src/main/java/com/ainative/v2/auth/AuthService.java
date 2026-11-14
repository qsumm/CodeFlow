package com.ainative.v2.auth;

import com.ainative.v2.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 身份域服务:注册/登录/当前用户。契约与 v1 /api/auth/* 对齐。 */
@Service
public class AuthService {
    private final UserDao users;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final com.ainative.v2.ws.WorkspaceProvisioner provisioner;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserDao users, JwtService jwt, JdbcTemplate jdbc,
                       com.ainative.v2.ws.WorkspaceProvisioner provisioner) {
        this.users = users;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.provisioner = provisioner;
    }

    /**
     * 注册并开户:自动创建一个「默认项目」并让新用户成为 owner。
     * 没有工作区的账号登录后前端无法取当前项目,所以开户必须与注册同事务完成。
     */
    @Transactional
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
        long workspaceId = createDefaultWorkspace(id, username.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("username", username.trim());
        out.put("nickname", nickname == null ? username.trim() : nickname);
        out.put("email", email);
        out.put("isAdmin", false);
        out.put("workspaceId", workspaceId);
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
        Long target = workspaceId;
        if (target == null) {
            // 未指定工作区时落到该用户的第一个项目;老账号(迁移前)可能还没有,现场开户。
            target = firstWorkspaceOf(user.id());
            if (target == null) {
                target = createDefaultWorkspace(user.id(), user.username());
            }
        }
        // 前端契约(v1 LoginResponse):userId/accessToken/refreshToken/user
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.id());
        userInfo.put("username", user.username());
        userInfo.put("nickname", user.nickname() == null ? user.username() : user.nickname());
        userInfo.put("email", user.email());
        userInfo.put("isAdmin", user.admin());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", user.id());
        out.put("accessToken", jwt.issue(user.id(), target));
        out.put("refreshToken", jwt.issueRefresh(user.id(), target));
        out.put("user", userInfo);
        out.put("workspace", target);
        return out;
    }

    private Long firstWorkspaceOf(long userId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT o.id FROM id_org o JOIN id_org_member m ON m.org_id=o.id AND m.user_id=?"
                        + " WHERE o.deleted_at IS NULL AND m.deleted_at IS NULL ORDER BY o.id LIMIT 1",
                Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long createDefaultWorkspace(long userId, String username) {
        jdbc.update("INSERT INTO id_org (name, owner_id, description) VALUES (?,?,?)",
                "默认项目", userId, "注册自动创建");
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM id_org", Long.class);
        jdbc.update("INSERT INTO id_org_member (org_id, user_id, role) VALUES (?,?, 'OWNER')", id, userId);
        if (id != null) {
            provisioner.ensureDefaultFlow(id);
        }
        return id == null ? 0L : id;
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
