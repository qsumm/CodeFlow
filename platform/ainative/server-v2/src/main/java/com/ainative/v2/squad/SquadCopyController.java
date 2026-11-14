package com.ainative.v2.squad;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小队跨项目复制:检查可复制性 / 目标项目列表 / 执行复制。
 * 复制 = 在目标项目重建小队与成员(成员即智能体引用,跨项目共享同一套 ag_agent)。
 */
@RestController
public class SquadCopyController {
    private final JdbcTemplate jdbc;

    public SquadCopyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    @GetMapping("/api/squads/{id}/copy-check")
    public ApiResult<Map<String, Object>> copyCheck(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        Integer members = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sq_member WHERE squad_id=? AND deleted_at IS NULL", Integer.class, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("copyable", true);
        out.put("memberCount", members == null ? 0 : members);
        out.put("reasons", List.of());
        return ApiResult.ok(out);
    }

    @GetMapping("/api/squads/{id}/copy-targets")
    public ApiResult<PageResult<Map<String, Object>>> copyTargets(@PathVariable long id,
                                                                  @RequestParam(required = false) String keyword,
                                                                  @RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        require(id);
        int limit = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        StringBuilder where = new StringBuilder(
                " WHERE o.deleted_at IS NULL AND o.id<>" + ws()
                        + " AND (m.user_id IS NOT NULL OR EXISTS (SELECT 1 FROM id_user u WHERE u.id="
                        + RequestContext.requireUserId() + " AND u.is_admin=1))");
        List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND o.name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM id_org o LEFT JOIN id_org_member m ON m.org_id=o.id AND m.deleted_at IS NULL"
                        + where, Integer.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DISTINCT o.id, o.name, o.description FROM id_org o"
                        + " LEFT JOIN id_org_member m ON m.org_id=o.id AND m.deleted_at IS NULL" + where
                        + " ORDER BY o.id DESC LIMIT " + limit + " OFFSET " + offset, pageArgs.toArray());
        return ApiResult.ok(new PageResult<>(rows, total == null ? 0 : total, page, limit));
    }

    private void require(long id) {
        if (jdbc.queryForList("SELECT 1 FROM sq_squad WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                id, ws()).isEmpty()) {
            throw new BizException("19001", "小队不存在");
        }
    }
}
