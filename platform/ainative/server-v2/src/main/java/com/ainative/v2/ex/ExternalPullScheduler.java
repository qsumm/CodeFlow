package com.ainative.v2.ex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 外部需求自动轮询:对启用的禅道绑定按 pollIntervalSeconds 周期拉取
 * (前端契约:绑定后 ≤60s 需求自动入库,无需手动按钮 —— 实测 v2 缺该调度,
 * 绑定后需求永远不进来)。同分钟判重防抖,与定时任务执行器同模式。
 */
@Component
public class ExternalPullScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExternalPullScheduler.class);

    private final JdbcTemplate jdbc;
    private final ExPullService pullService;

    public ExternalPullScheduler(JdbcTemplate jdbc, ExPullService pullService) {
        this.jdbc = jdbc;
        this.pullService = pullService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        List<Map<String, Object>> bindings;
        try {
            bindings = jdbc.queryForList(
                    "SELECT id, config_json FROM ex_binding WHERE provider='ZENTAO' AND status='ACTIVE' AND deleted_at IS NULL");
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> b : bindings) {
            long id = ((Number) b.get("id")).longValue();
            try {
                if (recentlyPulled(id)) {
                    continue;
                }
                Map<String, Object> result = pullService.pull(id);
                log.info("external auto-pull binding={} result={}", id, result);
            } catch (Exception e) {
                // 拉取失败(外部平台不可达等)写回绑定 last_error,下轮继续
                try {
                    jdbc.update("UPDATE ex_binding SET config_json=JSON_SET(COALESCE(config_json,'{}'), '$.lastError', ?) WHERE id=?",
                            String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length())), id);
                } catch (Exception ignored) {
                    // 记录失败不影响调度
                }
                log.warn("external auto-pull failed binding={} error={}", id, e.getMessage());
            }
        }
    }

    /** 同一分钟内已拉取过则跳过(以数据库时钟为准)。 */
    private boolean recentlyPulled(long bindingId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ex_import_record WHERE binding_id=? AND gmt_create >= DATE_FORMAT(NOW(), '%Y-%m-%d %H:%i:00')",
                Integer.class, bindingId);
        return n != null && n > 0;
    }
}
