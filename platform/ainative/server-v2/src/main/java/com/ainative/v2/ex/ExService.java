package com.ainative.v2.ex;

import com.ainative.v2.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 外部连接域服务:绑定 CRUD/状态映射/通知偏好。远端同步引擎(provider 客户端)不在本域范围。 */
@Service
public class ExService {
    private final ExDao dao;

    public ExService(ExDao dao) {
        this.dao = dao;
    }

    public List<Map<String, Object>> bindings(String provider) {
        return dao.bindings(normalize(provider)).stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("provider", b.provider());
            m.put("projectId", b.projectId());
            m.put("name", b.name());
            m.put("status", b.status());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> createBinding(String provider, String projectId, String name, String credentialRef, String baseUrl) {
        String p = normalize(provider);
        if (projectId == null || projectId.isBlank()) {
            throw new BizException("10001", "外部项目 ID 不能为空");
        }
        String config = baseUrl == null || baseUrl.isBlank() ? null
                : "{\"baseUrl\":\"" + baseUrl.trim().replace("\"", "") + "\"}";
        long id = dao.insertBinding(p, projectId.trim(), name, credentialRef, config);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("provider", p);
        m.put("projectId", projectId.trim());
        m.put("status", "ACTIVE");
        return m;
    }

    @Transactional
    public void deleteBinding(long id) {
        dao.findBinding(id).orElseThrow(() -> new BizException("18001", "绑定不存在"));
        dao.deleteBinding(id);
    }

    public List<Map<String, Object>> statusMappings(long bindingId) {
        dao.findBinding(bindingId).orElseThrow(() -> new BizException("18001", "绑定不存在"));
        return dao.statusMaps(bindingId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("externalStatus", s.externalStatus());
            m.put("stateId", s.stateId());
            return m;
        }).toList();
    }

    @Transactional
    public List<Map<String, Object>> upsertStatusMapping(long bindingId, String externalStatus, long stateId) {
        dao.findBinding(bindingId).orElseThrow(() -> new BizException("18001", "绑定不存在"));
        if (externalStatus == null || externalStatus.isBlank()) {
            throw new BizException("10001", "外部状态不能为空");
        }
        dao.upsertStatusMap(bindingId, externalStatus.trim(), stateId);
        return statusMappings(bindingId);
    }

    public List<Map<String, Object>> prefs(long userId) {
        return dao.prefsOf(userId);
    }

    @Transactional
    public void savePrefs(long userId, List<Map<String, Object>> items) {
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            Object eventType = item.get("type");
            if (eventType == null || String.valueOf(eventType).isBlank()) {
                continue;
            }
            dao.upsertPref(userId, String.valueOf(eventType),
                    Boolean.TRUE.equals(item.get("inApp")),
                    Boolean.TRUE.equals(item.get("imChannel")));
        }
    }

    private static String normalize(String provider) {
        String p = provider == null ? "" : provider.trim().toUpperCase();
        if (!p.equals("ZENTAO")) {
            throw new BizException("10001", "不支持的外部平台: " + provider);
        }
        return p;
    }
}
