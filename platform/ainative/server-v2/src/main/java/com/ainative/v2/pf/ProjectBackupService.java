package com.ainative.v2.pf;

import com.ainative.v2.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目配置备份引擎。
 *
 * <p>职责:把 SDLC 流程、智能体、小队、仓库、技能、工作项状态机等「配置类」数据
 * 导出成一份自描述 JSON 归档,落盘到受控目录并登记到 pf_backup;恢复时按主键
 * 幂等 upsert 回写。数据类(工单、运行记录、消息)不参与备份 —— 它们有各自的
 * 生命周期与审计要求,归档只覆盖「可重建的环境与策略」。
 *
 * <p>归档格式:
 * <pre>
 * {
 *   "schemaVersion": "codeflow.backup.v1",
 *   "createdAt": "...",
 *   "tables": { "&lt;表名&gt;": [ { "列": 值, ... } ] }
 * }
 * </pre>
 */
@Service
public class ProjectBackupService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 导出表 → 列顺序(同时决定恢复时的 upsert 顺序)。 */
    private static final Map<String, List<String>> EXPORT_TABLES = new LinkedHashMap<>();

    static {
        EXPORT_TABLES.put("sq_sdlc", List.of("id", "name", "description", "status"));
        EXPORT_TABLES.put("sq_sdlc_step", List.of("id", "sdlc_id", "seq", "name", "kind", "instruction", "required"));
        EXPORT_TABLES.put("ag_agent", List.of("id", "name", "role_code", "role_name", "soul_md", "status"));
        EXPORT_TABLES.put("sq_squad", List.of("id", "name", "description", "status"));
        EXPORT_TABLES.put("sq_member", List.of("id", "squad_id", "agent_id", "role_code"));
        EXPORT_TABLES.put("as_repo", List.of("id", "name", "url", "provider", "default_branch"));
        EXPORT_TABLES.put("mm_skill", List.of("id", "name", "version_label", "status"));
        EXPORT_TABLES.put("wi_flow", List.of("id", "name", "is_default", "status"));
        EXPORT_TABLES.put("wi_flow_state", List.of("id", "flow_id", "name", "category", "sort_no"));
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root;

    public ProjectBackupService(JdbcTemplate jdbc,
                                @Value("${ainative.artifact-dir:}") String configuredDir) {
        this.jdbc = jdbc;
        String dir = configuredDir == null || configuredDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/ainative-artifacts"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath().resolve("backups");
    }

    // ---------------------------------------------------------------- 备份

    /**
     * 执行一次备份。过程分三步落库:RUNNING → 归档写盘 → SUCCEEDED/FAILED,
     * 任一步抛错都回写 FAILED 并带上错误摘要,便于前端列表直接呈现。
     */
    public Map<String, Object> backup(String name) {
        String backupName = (name == null || name.isBlank() ? "配置备份" : name.trim()) + "-" + TS.format(LocalDateTime.now());
        jdbc.update("INSERT INTO pf_backup (workspace_id, name, status, size_bytes) VALUES (1,?, 'RUNNING', 0)", backupName);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM pf_backup WHERE workspace_id=1", Long.class);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", "codeflow.backup.v1");
            payload.put("createdAt", LocalDateTime.now().toString());
            Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : EXPORT_TABLES.entrySet()) {
                tables.put(e.getKey(), exportTable(e.getKey(), e.getValue()));
            }
            payload.put("tables", tables);
            byte[] bytes = mapper.writeValueAsBytes(payload);

            Files.createDirectories(root);
            Path file = root.resolve("backup-" + id + ".json");
            Files.write(file, bytes);
            String sha = sha256(bytes);

            jdbc.update("UPDATE pf_backup SET status='SUCCEEDED', object_key=?, size_bytes=?, sha256=?, finished_at=NOW(), detail_json=? WHERE id=?",
                    root.relativize(file).toString(), bytes.length, sha, new String(bytes, StandardCharsets.UTF_8), id);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", String.valueOf(id));
            out.put("status", "SUCCEEDED");
            out.put("ossRef", root.relativize(file).toString());
            out.put("sizeBytes", bytes.length);
            out.put("sha256", sha);
            out.put("createdAt", LocalDateTime.now().toString());
            out.put("finishedAt", LocalDateTime.now().toString());
            out.put("tables", EXPORT_TABLES.keySet());
            return out;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbc.update("UPDATE pf_backup SET status='FAILED', error_message=?, finished_at=NOW() WHERE id=?",
                    msg.length() > 500 ? msg.substring(0, 500) : msg, id);
            throw new BizException("10501", "备份失败:" + msg);
        }
    }

    private List<Map<String, Object>> exportTable(String table, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> actual = existingColumns(table, columns);
        if (actual.isEmpty()) {
            // 表不存在(旧库未建)→ 记空数组,不阻断整体备份。
            return List.of();
        }
        try {
            jdbc.query("SELECT " + String.join(", ", actual) + " FROM " + table + " WHERE workspace_id=1"
                            + (hasColumn(table, "deleted_at") ? " AND deleted_at IS NULL" : ""),
                    rs -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < actual.size(); i++) {
                            row.put(actual.get(i), rs.getObject(i + 1));
                        }
                        rows.add(row);
                    });
        } catch (Exception e) {
            // 单表读取失败不应让整体备份失败,记空数组即可。
            return List.of();
        }
        return rows;
    }

    /**
     * 取白名单与实际表结构的交集。备份不能因为某列不存在就整表丢失 ——
     * 与其静默写出一个缺列的归档,不如少存几列,并在恢复时按实际列回填。
     */
    private List<String> existingColumns(String table, List<String> wanted) {
        Set<String> present = tableColumns(table);
        if (present.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String c : wanted) {
            if (present.contains(c.toLowerCase())) {
                out.add(c);
            }
        }
        return out;
    }

    private final Map<String, Set<String>> columnCache = new ConcurrentHashMap<>();

    /** 表列集合(小写);表不存在时返回空集。 */
    private Set<String> tableColumns(String table) {
        return columnCache.computeIfAbsent(table, t -> {
            Set<String> cols = new LinkedHashSet<>();
            try {
                var ds = jdbc.getDataSource();
                if (ds == null) {
                    return cols;
                }
                try (Connection conn = ds.getConnection()) {
                    DatabaseMetaData md = conn.getMetaData();
                    try (ResultSet rs = md.getColumns(conn.getCatalog(), null, t, null)) {
                        while (rs.next()) {
                            cols.add(rs.getString("COLUMN_NAME").toLowerCase());
                        }
                    }
                }
            } catch (Exception e) {
                return cols;
            }
            return cols;
        });
    }

    private boolean hasColumn(String table, String column) {
        return tableColumns(table).contains(column.toLowerCase());
    }

    // ---------------------------------------------------------------- 列表

    public List<Map<String, Object>> list(int page, int size) {
        int limit = Math.max(1, Math.min(size, 200));
        int offset = Math.max(0, (Math.max(page, 1) - 1) * limit);
        return jdbc.queryForList(
                "SELECT id, name, status, object_key AS ossRef, size_bytes AS sizeBytes, sha256, error_message AS errorMessage,"
                        + " gmt_create AS createdAt, finished_at AS finishedAt FROM pf_backup"
                        + " WHERE workspace_id=1 AND deleted_at IS NULL ORDER BY id DESC LIMIT " + limit + " OFFSET " + offset);
    }

    // ---------------------------------------------------------------- 恢复

    /** 按主键幂等恢复(upsert)。 */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> restore(long backupId) {
        byte[] bytes = archiveBytes(backupId);
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            throw new BizException("10502", "备份内容解析失败");
        }
        Object tablesObj = payload.get("tables");
        if (!(tablesObj instanceof Map)) {
            throw new BizException("10502", "备份内容格式不正确");
        }
        Map<String, List<Map<String, Object>>> tables = (Map<String, List<Map<String, Object>>>) tablesObj;
        int restored = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : tables.entrySet()) {
            List<String> cols = EXPORT_TABLES.get(e.getKey());
            if (cols == null) {
                continue;
            }
            for (Map<String, Object> row : e.getValue()) {
                restoreRow(e.getKey(), cols, row);
                restored++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backupId", backupId);
        out.put("restoredRows", restored);
        return out;
    }

    private void restoreRow(String table, List<String> cols, Map<String, Object> row) {
        StringBuilder colsSql = new StringBuilder();
        StringBuilder valsSql = new StringBuilder();
        List<Object> insertArgs = new ArrayList<>();
        StringBuilder updates = new StringBuilder();
        List<Object> updateArgs = new ArrayList<>();
        for (String c : cols) {
            if (!row.containsKey(c)) {
                continue;
            }
            if (colsSql.length() > 0) {
                colsSql.append(",");
                valsSql.append(",");
            }
            colsSql.append(c);
            valsSql.append("?");
            Object v = normalize(row.get(c));
            insertArgs.add(v);
            if (!"id".equals(c)) {
                updates.append(updates.length() == 0 ? "" : ",").append(c).append("=?");
                updateArgs.add(v);
            }
        }
        // 归档只存业务列;workspace_id 是分区键,恢复时按当前工作区补齐。
        if (hasColumn(table, "workspace_id") && !row.containsKey("workspace_id")) {
            colsSql.append(",workspace_id");
            valsSql.append(",?");
            insertArgs.add(1L);
        }
        if (colsSql.length() == 0) {
            return;
        }
        if (updates.length() > 0) {
            // 占位符顺序 = INSERT 列值 → UPDATE 赋值,参数必须同序。
            // ON DUPLICATE KEY UPDATE 的冲突键就是 INSERT 的主键,无需再传 id。
            List<Object> all = new ArrayList<>(insertArgs);
            all.addAll(updateArgs);
            jdbc.update("INSERT INTO " + table + " (" + colsSql + ") VALUES (" + valsSql + ")"
                    + " ON DUPLICATE KEY UPDATE " + updates, all.toArray());
        } else {
            jdbc.update("INSERT IGNORE INTO " + table + " (" + colsSql + ") VALUES (" + valsSql + ")", insertArgs.toArray());
        }
    }

    // ---------------------------------------------------------------- 下载

    /** 读取归档文件字节;优先读盘,回落数据库 detail_json。 */
    public byte[] archiveBytes(long backupId) {
        String[] holder = new String[2];
        jdbc.query("SELECT object_key, detail_json FROM pf_backup WHERE id=? AND workspace_id=1", rs -> {
            holder[0] = rs.getString("object_key");
            holder[1] = rs.getString("detail_json");
        }, backupId);
        if (holder[0] == null && holder[1] == null) {
            throw new BizException("21002", "备份不存在");
        }
        if (holder[0] != null && !holder[0].isBlank()) {
            Path file = root.resolve(holder[0]);
            if (Files.isReadable(file)) {
                try {
                    return Files.readAllBytes(file);
                } catch (IOException e) {
                    throw new BizException("10503", "备份归档读取失败");
                }
            }
        }
        if (holder[1] == null || holder[1].isBlank()) {
            throw new BizException("21002", "备份内容为空");
        }
        return holder[1].getBytes(StandardCharsets.UTF_8);
    }

    public String archiveFileName(long backupId) {
        List<String> names = jdbc.queryForList("SELECT name FROM pf_backup WHERE id=?", String.class, backupId);
        String name = names.isEmpty() ? ("backup-" + backupId) : names.get(0);
        String ascii = name.replaceAll("[^A-Za-z0-9._-]", "");
        if (ascii.isBlank()) {
            ascii = "backup-" + backupId;
        }
        return ascii + ".json";
    }

    // ---------------------------------------------------------------- 工具

    private Object normalize(Object v) {
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return v;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("10504", "摘要计算失败");
        }
    }
}
