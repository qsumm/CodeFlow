package com.ainative.v2.pf;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 平台运维域服务:品牌/设置/审计/通知/定时任务/IM 渠道/自进化/备份/调试日志。 */
@Service
public class PfService {
    private final PfDao dao;

    public PfService(PfDao dao) {
        this.dao = dao;
    }

    public Map<String, Object> publicBranding() {
        return dao.branding().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platformName", b.platformName() == null ? "CodeFlow" : b.platformName());
            m.put("themeKey", b.themeKey() == null ? "ocean-blue" : b.themeKey());
            m.put("primaryColor", b.primaryColor());
            m.put("domain", b.domain());
            m.put("deploymentVersion", "2.0.0");
            m.put("canManage", false);
            return m;
        }).orElseGet(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platformName", "CodeFlow");
            m.put("themeKey", "ocean-blue");
            m.put("primaryColor", "#2383e2");
            m.put("domain", null);
            m.put("deploymentVersion", "2.0.0");
            m.put("canManage", false);
            return m;
        });
    }

    @Transactional
    public Map<String, Object> saveBranding(String name, String themeKey, String color, String domain) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "平台名称不能为空");
        }
        dao.upsertBranding(name.trim(), themeKey, color, domain);
        return publicBranding();
    }

    public List<Map<String, Object>> settings(String group) {
        return dao.settings(group == null ? "SYSTEM" : group.toUpperCase());
    }

    @Transactional
    public void saveSettings(String group, List<Map<String, Object>> items) {
        String g = group == null ? "SYSTEM" : group.toUpperCase();
        if (items == null) {
            return;
        }
        for (Map<String, Object> item : items) {
            Object key = item.get("key");
            if (key == null || String.valueOf(key).isBlank()) {
                continue;
            }
            dao.upsertSetting(g, String.valueOf(key), item.get("valueJson") == null ? null : String.valueOf(item.get("valueJson")),
                    Boolean.TRUE.equals(item.get("isSecret")));
        }
    }

    public PageResult<Map<String, Object>> auditLogs(int pageNum, int pageSize) {
        return new PageResult<>(dao.auditLogs(pageNum, pageSize), dao.countAudit(), pageNum, pageSize);
    }

    @Transactional
    public void audit(long actorId, String action, String targetType, Long targetId) {
        dao.audit(actorId, action, targetType, targetId);
    }

    public PageResult<Map<String, Object>> notifications(long userId, int pageNum, int pageSize) {
        return new PageResult<>(dao.notifications(userId, pageNum, pageSize), dao.unreadCount(userId), pageNum, pageSize);
    }

    public long unreadCount(long userId) {
        return dao.unreadCount(userId);
    }

    @Transactional
    public void markRead(long id) {
        dao.markRead(id);
    }

    @Transactional
    public void markAllRead(long userId) {
        dao.markAllRead(userId);
    }

    @Transactional
    public void deleteNotification(long id) {
        dao.deleteNotification(id);
    }

    @Transactional
    public Map<String, Object> notify(long userId, String eventType, String title, String bodyMd) {
        long id = dao.insertNotification(userId, eventType, title, bodyMd);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        return m;
    }

    // ── 定时任务 ──
    public List<Map<String, Object>> tasks() {
        return dao.tasks().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("name", t.name());
            m.put("cronExpr", t.cronExpr());
            m.put("enabled", t.enabled());
            return m;
        }).toList();
    }

    public Map<String, Object> task(long id) {
        PfDao.Task t = dao.findTask(id).orElseThrow(() -> new BizException("21001", "定时任务不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("cronExpr", t.cronExpr());
        m.put("enabled", t.enabled());
        m.put("runs", dao.taskRuns(id));
        return m;
    }

    @Transactional
    public Map<String, Object> createTask(String name, String cron, String templateJson) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "任务名称不能为空");
        }
        return task(dao.insertTask(name.trim(), cron, templateJson));
    }

    @Transactional
    public Map<String, Object> updateTask(long id, String name, String cron, String templateJson) {
        dao.findTask(id).orElseThrow(() -> new BizException("21001", "定时任务不存在"));
        dao.updateTask(id, name, cron, templateJson);
        return task(id);
    }

    @Transactional
    public Map<String, Object> setTaskEnabled(long id, boolean enabled) {
        dao.findTask(id).orElseThrow(() -> new BizException("21001", "定时任务不存在"));
        dao.setTaskEnabled(id, enabled);
        return task(id);
    }

    @Transactional
    public void deleteTask(long id) {
        dao.findTask(id).orElseThrow(() -> new BizException("21001", "定时任务不存在"));
        dao.deleteTask(id);
    }

    // ── IM 渠道 ──
    public List<Map<String, Object>> imChannels() {
        return dao.imChannels();
    }

    @Transactional
    public List<Map<String, Object>> saveImChannel(String channel, String appKey, boolean enabled) {
        if (channel == null || channel.isBlank()) {
            throw new BizException("10001", "渠道不能为空");
        }
        dao.upsertImChannel(channel.toUpperCase(), appKey, enabled);
        return imChannels();
    }

    public Map<String, Object> imSelection() {
        return dao.imSelection().orElseGet(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", "DINGTALK");
            m.put("generation", 0);
            return m;
        });
    }

    @Transactional
    public Map<String, Object> selectImChannel(String provider) {
        if (provider == null || provider.isBlank()) {
            throw new BizException("10001", "渠道不能为空");
        }
        dao.setImSelection(provider.toUpperCase());
        return imSelection();
    }

    // ── 其它只读 ──
    public List<Map<String, Object>> evolutionProposals() {
        return dao.evolutionProposals();
    }

    public List<Map<String, Object>> backups() {
        return dao.backups();
    }

    public List<Map<String, Object>> debugLogs(long ticketId) {
        return dao.debugLogs(ticketId);
    }
}
