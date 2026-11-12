package com.ainative.v2.asset;

import com.ainative.v2.common.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 资产与仓库域服务:仓库/结论/关系、环境变量(值脱敏)、产物列表。 */
@Service
public class AssetService {
    private final AssetDao dao;

    public AssetService(AssetDao dao) {
        this.dao = dao;
    }

    public List<Map<String, Object>> listRepos() {
        return dao.listRepos().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("name", r.name());
            m.put("url", r.url());
            m.put("provider", r.provider());
            m.put("defaultBranch", r.defaultBranch());
            return m;
        }).toList();
    }

    public Map<String, Object> repo(long id) {
        AssetDao.Repo r = dao.findRepo(id).orElseThrow(() -> new BizException("20001", "仓库不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("url", r.url());
        m.put("provider", r.provider());
        m.put("defaultBranch", r.defaultBranch());
        m.put("conclusion", dao.conclusion(id).orElse(null));
        return m;
    }

    @Transactional
    public Map<String, Object> createRepo(String name, String url, String provider, String defaultBranch) {
        if (url == null || url.isBlank()) {
            throw new BizException("10001", "仓库地址不能为空");
        }
        return repo(dao.insertRepo(name == null || name.isBlank() ? url : name.trim(), url.trim(),
                provider == null ? "GIT" : provider, defaultBranch == null ? "main" : defaultBranch));
    }

    @Transactional
    public Map<String, Object> updateRepo(long id, String name, String url, String provider, String defaultBranch) {
        dao.findRepo(id).orElseThrow(() -> new BizException("20001", "仓库不存在"));
        dao.updateRepo(id, name, url, provider, defaultBranch);
        return repo(id);
    }

    @Transactional
    public void deleteRepo(long id) {
        dao.findRepo(id).orElseThrow(() -> new BizException("20001", "仓库不存在"));
        dao.deleteRepo(id);
    }

    public List<Map<String, Object>> relations() {
        return dao.relations();
    }

    @Transactional
    public Map<String, Object> saveConclusion(long repoId, String contentMd) {
        dao.findRepo(repoId).orElseThrow(() -> new BizException("20001", "仓库不存在"));
        dao.saveConclusion(repoId, contentMd);
        return repo(repoId);
    }

    public List<Map<String, Object>> envVars() {
        return dao.listEnvVars().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("key", e.keyName());
            // 敏感值只回掩码
            m.put("value", e.secret() ? "******" : e.valueRef());
            m.put("secret", e.secret());
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> createEnvVar(String keyName, String valueRef, boolean secret) {
        if (keyName == null || keyName.isBlank()) {
            throw new BizException("10001", "变量名不能为空");
        }
        long id = dao.insertEnvVar(keyName.trim(), valueRef, secret);
        return envVars().stream().filter(e -> Long.valueOf(id).equals(e.get("id"))).findFirst()
                .orElseThrow(() -> new BizException("20002", "环境变量创建失败"));
    }

    @Transactional
    public Map<String, Object> updateEnvVar(long id, String valueRef, boolean secret) {
        dao.findEnvVar(id).orElseThrow(() -> new BizException("20002", "环境变量不存在"));
        dao.updateEnvVar(id, valueRef, secret);
        return envVars().stream().filter(e -> Long.valueOf(id).equals(e.get("id"))).findFirst()
                .orElseThrow(() -> new BizException("20002", "环境变量不存在"));
    }

    @Transactional
    public void deleteEnvVar(long id) {
        dao.findEnvVar(id).orElseThrow(() -> new BizException("20002", "环境变量不存在"));
        dao.deleteEnvVar(id);
    }

    public List<Map<String, Object>> artifactsOf(long ticketId) {
        return dao.artifactsOf(ticketId).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("workitemId", a.ticketId());
            m.put("name", a.name());
            m.put("kind", a.kind());
            m.put("objectKey", a.objectKey());
            m.put("sizeBytes", a.sizeBytes());
            return m;
        }).toList();
    }
}
