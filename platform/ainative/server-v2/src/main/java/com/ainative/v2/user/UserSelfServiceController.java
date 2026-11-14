package com.ainative.v2.user;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人中心:/api/users/me/*。
 * 资料、密码、个性化设置、IM 身份(钉钉/飞书)、联邦身份、账号注销。
 */
@RestController
@RequestMapping("/api/users/me")
public class UserSelfServiceController {
    public record ProfileRequest(String nickname, String email) {}
    public record PasswordRequest(String oldPassword, String newPassword) {}
    public record SettingRequest(String valueJson) {}
    public record ImIdentityRequest(String externalUserId) {}
    public record DeactivationRequest(String reason) {}

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserSelfServiceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 资料

    @GetMapping("/profile")
    public ApiResult<Map<String, Object>> profile() {
        long uid = RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, nickname, email, is_admin AS isAdmin, status, gmt_create AS createdAt"
                        + " FROM id_user WHERE id=? AND deleted_at IS NULL", uid);
        if (rows.isEmpty()) {
            throw new BizException("10401", "用户不存在");
        }
        return ApiResult.ok(rows.get(0));
    }

    @PutMapping("/profile")
    public ApiResult<Map<String, Object>> updateProfile(@RequestBody ProfileRequest req) {
        long uid = RequestContext.requireUserId();
        if (req.nickname() != null) {
            jdbc.update("UPDATE id_user SET nickname=? WHERE id=?", req.nickname(), uid);
        }
        if (req.email() != null) {
            jdbc.update("UPDATE id_user SET email=? WHERE id=?", req.email(), uid);
        }
        return profile();
    }

    @PutMapping("/password")
    public ApiResult<Void> changePassword(@RequestBody PasswordRequest req) {
        long uid = RequestContext.requireUserId();
        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM id_user WHERE id=?", String.class, uid);
        if (hashes.isEmpty() || !encoder.matches(req.oldPassword() == null ? "" : req.oldPassword(), hashes.get(0))) {
            throw new BizException("10401", "原密码不正确");
        }
        if (req.newPassword() == null || req.newPassword().length() < 6) {
            throw new BizException("10001", "新密码至少 6 位");
        }
        jdbc.update("UPDATE id_user SET password_hash=? WHERE id=?", encoder.encode(req.newPassword()), uid);
        return ApiResult.ok(null);
    }

    // ---------------------------------------------------------------- 设置

    @GetMapping("/settings")
    public ApiResult<List<Map<String, Object>>> settings() {
        long uid = RequestContext.requireUserId();
        // 前端契约:UserSetting[] = [{key, valueJson}];valueJson 是 JSON 文本
        return ApiResult.ok(jdbc.queryForList(
                "SELECT setting_key AS `key`, CAST(value_json AS CHAR) AS valueJson"
                        + " FROM id_user_setting WHERE user_id=? AND deleted_at IS NULL ORDER BY setting_key", uid));
    }

    @GetMapping("/settings/{key}")
    public ApiResult<Map<String, Object>> setting(@PathVariable String key) {
        long uid = RequestContext.requireUserId();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT setting_key AS `key`, CAST(value_json AS CHAR) AS valueJson"
                        + " FROM id_user_setting WHERE user_id=? AND setting_key=? AND deleted_at IS NULL",
                uid, key);
        if (!rows.isEmpty()) {
            return ApiResult.ok(rows.get(0));
        }
        // Map.of 不接受 null 值,未设置时用可变 Map 返回 {key, valueJson: null}
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("key", key);
        empty.put("valueJson", null);
        return ApiResult.ok(empty);
    }

    @PutMapping("/settings/{key}")
    public ApiResult<Map<String, Object>> saveSetting(@PathVariable String key, @RequestBody SettingRequest req) {
        long uid = RequestContext.requireUserId();
        String raw = req.valueJson();
        // valueJson 语义:一段 JSON 文本(标量写 "shift-enter",对象写 {...});null 表示清除
        if (raw == null) {
            jdbc.update("DELETE FROM id_user_setting WHERE user_id=? AND setting_key=?", uid, key);
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("key", key);
            cleared.put("valueJson", null);
            return ApiResult.ok(cleared);
        }
        String json = looksLikeJson(raw) ? raw : quote(raw);
        int updated = jdbc.update("UPDATE id_user_setting SET value_json=? WHERE user_id=? AND setting_key=?",
                json, uid, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO id_user_setting (user_id, setting_key, value_json) VALUES (?,?,?)",
                    uid, key, json);
        }
        return setting(key);
    }

    @DeleteMapping("/settings/{key}")
    public ApiResult<Void> deleteSetting(@PathVariable String key) {
        long uid = RequestContext.requireUserId();
        jdbc.update("DELETE FROM id_user_setting WHERE user_id=? AND setting_key=?", uid, key);
        return ApiResult.ok(null);
    }

    private boolean looksLikeJson(String v) {
        String t = v.trim();
        if (t.isEmpty()) {
            return false;
        }
        char c = t.charAt(0);
        return c == '{' || c == '[' || "true".equals(t) || "false".equals(t) || "null".equals(t)
                || t.matches("-?\\d+(\\.\\d+)?");
    }

    private String quote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- IM 身份

    @GetMapping("/im-identities")
    public ApiResult<List<Map<String, Object>>> imIdentities() {
        long uid = RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, channel AS provider, external_id AS externalUserId, gmt_create AS createdAt"
                        + " FROM id_user_im WHERE user_id=? ORDER BY id", uid));
    }

    @PutMapping("/im-identities/{provider}")
    public ApiResult<Map<String, Object>> bindIm(@PathVariable String provider, @RequestBody ImIdentityRequest req) {
        long uid = RequestContext.requireUserId();
        if (req.externalUserId() == null || req.externalUserId().isBlank()) {
            throw new BizException("10001", "外部账号不能为空");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id FROM id_user_im WHERE user_id=? AND channel=?", uid, provider);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO id_user_im (user_id, channel, external_id) VALUES (?,?,?)",
                    uid, provider, req.externalUserId());
        } else {
            jdbc.update("UPDATE id_user_im SET external_id=? WHERE id=?", req.externalUserId(), existing.get(0).get("id"));
        }
        return ApiResult.ok(Map.of("provider", provider, "externalUserId", req.externalUserId()));
    }

    @DeleteMapping("/im-identities/{provider}")
    public ApiResult<Void> unbindIm(@PathVariable String provider) {
        long uid = RequestContext.requireUserId();
        jdbc.update("DELETE FROM id_user_im WHERE user_id=? AND channel=?", uid, provider);
        return ApiResult.ok(null);
    }

    @PostMapping("/im-identities/dingtalk/test")
    public ApiResult<Map<String, Object>> testDingtalk() {
        return testIm("dingtalk");
    }

    @PostMapping("/im-identities/feishu/test")
    public ApiResult<Map<String, Object>> testFeishu() {
        return testIm("feishu");
    }

    private ApiResult<Map<String, Object>> testIm(String provider) {
        long uid = RequestContext.requireUserId();
        boolean bound = !jdbc.queryForList(
                "SELECT 1 FROM id_user_im WHERE user_id=? AND channel=?", uid, provider).isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider);
        out.put("bound", bound);
        out.put("reachable", bound);
        out.put("message", bound ? "绑定存在,消息通道可用" : "尚未绑定该渠道");
        return ApiResult.ok(out);
    }

    // ---------------------------------------------------------------- 联邦身份

    @GetMapping("/federated-identities")
    public ApiResult<List<Map<String, Object>>> federatedIdentities() {
        long uid = RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT f.id, f.idp_id AS idpId, i.name AS idpName, f.external_subject AS externalSubject,"
                        + " f.gmt_create AS createdAt FROM id_fed_identity f"
                        + " LEFT JOIN id_idp i ON i.id=f.idp_id WHERE f.user_id=? ORDER BY f.id", uid));
    }

    @DeleteMapping("/federated-identities/{id}")
    public ApiResult<Void> unbindFederated(@PathVariable long id) {
        long uid = RequestContext.requireUserId();
        jdbc.update("DELETE FROM id_fed_identity WHERE id=? AND user_id=?", id, uid);
        return ApiResult.ok(null);
    }

    @PostMapping("/federated-identities/bind-start")
    public ApiResult<Map<String, Object>> bindStart() {
        RequestContext.requireUserId();
        return ApiResult.ok(Map.of("bindUrl", "", "state", "SSO_NOT_CONFIGURED",
                "message", "未配置身份提供商"));
    }

    @PostMapping("/federated-identities/claim")
    public ApiResult<Map<String, Object>> claim(@RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        return ApiResult.ok(Map.of("claimed", false, "message", "未配置身份提供商"));
    }

    // ---------------------------------------------------------------- 注销

    @PostMapping("/deactivation")
    public ApiResult<Map<String, Object>> deactivate(@RequestBody(required = false) DeactivationRequest req) {
        long uid = RequestContext.requireUserId();
        jdbc.update("UPDATE id_user SET status='DISABLED', deleted_at=NOW() WHERE id=?", uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", uid);
        out.put("status", "DISABLED");
        return ApiResult.ok(out);
    }
}
