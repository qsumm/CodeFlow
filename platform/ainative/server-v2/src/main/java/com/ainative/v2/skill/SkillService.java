package com.ainative.v2.skill;

import com.ainative.v2.common.BizException;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能域:安装型(MCP/PLUGIN/HOOK)与 OSS 包型技能的统一管理。
 *
 * <p>技能包是本地上传的 zip,落在受控目录 <code>artifact-dir/skills/&lt;skillId&gt;/</code>;
 * 包内 SKILL.md 的 frontmatter(name/description)用于自动补全元数据。
 */
@Service
public class SkillService {
    private final JdbcTemplate jdbc;
    private final Path root;

    public SkillService(JdbcTemplate jdbc, @Value("${ainative.artifact-dir:}") String configuredDir) {
        this.jdbc = jdbc;
        String dir = configuredDir == null || configuredDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/ainative-artifacts"
                : configuredDir;
        this.root = Paths.get(dir).toAbsolutePath().resolve("skills");
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    // ---------------------------------------------------------------- 查询

    public PageResult<Map<String, Object>> list(String keyword, Long categoryId, String typeHint,
                                                int pageNum, int pageSize) {
        int limit = Math.max(1, Math.min(pageSize, 100));
        int offset = Math.max(0, (Math.max(pageNum, 1) - 1) * limit);
        StringBuilder where = new StringBuilder(" WHERE s.workspace_id=? AND s.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(ws());
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (s.name LIKE ? OR s.description LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (categoryId != null) {
            where.append(" AND s.category_id=?");
            args.add(categoryId);
        }
        if (typeHint != null && !typeHint.isBlank()) {
            where.append(" AND s.skill_type=?");
            args.add(typeHint);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM mm_skill s" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.skill_type AS type, s.name, s.install_spec AS installSpec, s.description,"
                        + " s.category_id AS categoryId, c.name AS categoryName, s.source_type AS sourceType,"
                        + " s.package_ref AS packageOssRef, s.package_file_name AS packageFileName,"
                        + " s.package_size AS packageSize, s.package_md5 AS packageMd5, s.version,"
                        + " s.gmt_create AS gmtCreate, s.gmt_modified AS gmtModified, s.modifier_id AS modifierId"
                        + " FROM mm_skill s LEFT JOIN as_category c ON c.id=s.category_id" + where
                        + " ORDER BY s.id DESC LIMIT " + limit + " OFFSET " + offset, pageArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, pageNum, limit);
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.skill_type AS type, s.name, s.install_spec AS installSpec, s.description,"
                        + " s.category_id AS categoryId, c.name AS categoryName, s.source_type AS sourceType,"
                        + " s.package_ref AS packageOssRef, s.package_file_name AS packageFileName,"
                        + " s.package_size AS packageSize, s.package_md5 AS packageMd5, s.version,"
                        + " s.gmt_create AS gmtCreate, s.gmt_modified AS gmtModified"
                        + " FROM mm_skill s LEFT JOIN as_category c ON c.id=s.category_id"
                        + " WHERE s.id=? AND s.workspace_id=? AND s.deleted_at IS NULL", id, ws());
        if (rows.isEmpty()) {
            throw new BizException("18001", "技能不存在");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 写入

    public Map<String, Object> create(Map<String, Object> body, long userId) {
        String name = str(body, "name");
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "技能名称不能为空");
        }
        String type = strOr(body, "type", "SKILL").toUpperCase();
        jdbc.update("INSERT INTO mm_skill (workspace_id, name, skill_type, install_spec, description,"
                        + " category_id, source_type, status, modifier_id) VALUES (?,?,?,?,?,?,?, 'ACTIVE', ?)",
                ws(), name.trim(), type, str(body, "installSpec"), str(body, "description"),
                longOrNull(body, "categoryId"), strOr(body, "sourceType", "INSTALL_SPEC"), userId);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM mm_skill WHERE workspace_id=?", Long.class, ws());
        return get(id);
    }

    public Map<String, Object> update(long id, Map<String, Object> body, long userId) {
        require(id);
        List<Object> args = new ArrayList<>();
        StringBuilder set = new StringBuilder();
        for (String[] pair : new String[][]{{"name", "name"}, {"installSpec", "install_spec"},
                {"description", "description"}, {"type", "skill_type"}}) {
            String v = str(body, pair[0]);
            if (v != null) {
                set.append(set.length() == 0 ? "" : ",").append(pair[1]).append("=?");
                args.add(v);
            }
        }
        if (body.containsKey("categoryId")) {
            set.append(set.length() == 0 ? "" : ",").append("category_id=?");
            args.add(longOrNull(body, "categoryId"));
        }
        if (set.length() > 0) {
            set.append(", modifier_id=?");
            args.add(userId);
            args.add(id);
            jdbc.update("UPDATE mm_skill SET " + set + " WHERE id=?", args.toArray());
        }
        return get(id);
    }

    public void delete(long id) {
        require(id);
        jdbc.update("UPDATE mm_skill SET deleted_at=NOW() WHERE id=?", id);
    }

    public void setCategory(long id, Long categoryId) {
        require(id);
        jdbc.update("UPDATE mm_skill SET category_id=? WHERE id=?", categoryId, id);
    }

    public int batchCategory(List<Long> skillIds, Long categoryId) {
        int n = 0;
        for (Long id : skillIds) {
            n += jdbc.update("UPDATE mm_skill SET category_id=? WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                    categoryId, id, ws());
        }
        return n;
    }

    // ---------------------------------------------------------------- 技能包

    /** 上传(或替换)技能包。 */
    public Map<String, Object> uploadPackage(long skillId, MultipartFile file) {
        require(skillId);
        if (file == null || file.isEmpty()) {
            throw new BizException("10001", "技能包不能为空");
        }
        String fileName = file.getOriginalFilename() == null ? "skill.zip" : file.getOriginalFilename();
        if (!fileName.toLowerCase().endsWith(".zip")) {
            throw new BizException("18002", "仅支持 zip 技能包");
        }
        try {
            byte[] bytes = file.getBytes();
            Path dir = root.resolve(String.valueOf(skillId));
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitize(fileName));
            Files.write(target, bytes);
            jdbc.update("UPDATE mm_skill SET package_ref=?, package_file_name=?, package_size=?, package_md5=?,"
                            + " source_type='OSS_ZIP' WHERE id=?",
                    root.relativize(target).toString(), fileName, bytes.length, md5(bytes), skillId);
        } catch (Exception e) {
            throw new BizException("18003", "技能包保存失败:" + e.getMessage());
        }
        return get(skillId);
    }

    /** 解析上传的包,返回前端确认弹窗需要的元数据。 */
    public Map<String, Object> inspectPackage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("10001", "技能包不能为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String fileName = file.getOriginalFilename() == null ? "skill.zip" : file.getOriginalFilename();
        String name = fileName.replaceAll("\\.zip$", "");
        String description = "";
        try (InputStream in = new ByteArrayInputStream(file.getBytes());
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (!entry.isDirectory() && (entryName.endsWith("SKILL.md") || entryName.endsWith("skill.md"))) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    zip.transferTo(buf);
                    String md = buf.toString(StandardCharsets.UTF_8);
                    Map<String, String> fm = frontMatter(md);
                    if (fm.containsKey("name")) {
                        name = fm.get("name");
                    }
                    if (fm.containsKey("description")) {
                        description = fm.get("description");
                    }
                    break;
                }
            }
        } catch (Exception e) {
            throw new BizException("18004", "技能包解析失败:" + e.getMessage());
        }
        out.put("name", name);
        out.put("description", description);
        out.put("fileName", fileName);
        out.put("packageSize", file.getSize());
        return out;
    }

    public Map<String, Object> listPackageFiles(long skillId) {
        Path dir = packageDir(skillId);
        List<Map<String, Object>> files = new ArrayList<>();
        try (InputStream in = Files.newInputStream(dir.resolve(packageFile(skillId)));
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", e.getName());
                f.put("name", e.getName().replaceAll("/$", ""));
                f.put("dir", e.isDirectory());
                f.put("size", e.getSize());
                f.put("kind", e.isDirectory() ? "DIR" : kindOf(e.getName()));
                files.add(f);
            }
        } catch (Exception e) {
            throw new BizException("18005", "技能包读取失败:" + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("format", "zip");
        return out;
    }

    public Map<String, Object> readPackageFile(long skillId, String path) {
        if (path == null || path.contains("..")) {
            throw new BizException("18006", "非法路径");
        }
        try (InputStream in = Files.newInputStream(packageDir(skillId).resolve(packageFile(skillId)));
             ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(path)) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    zip.transferTo(buf);
                    byte[] bytes = buf.toByteArray();
                    boolean binary = !isText(bytes);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", path);
                    out.put("fileName", path.substring(path.lastIndexOf('/') + 1));
                    out.put("content", binary ? java.util.Base64.getEncoder().encodeToString(bytes)
                            : new String(bytes, StandardCharsets.UTF_8));
                    out.put("binary", binary);
                    return out;
                }
            }
        } catch (Exception e) {
            throw new BizException("18005", "技能包读取失败:" + e.getMessage());
        }
        throw new BizException("18007", "包内文件不存在:" + path);
    }

    public byte[] packageBytes(long skillId) {
        try {
            return Files.readAllBytes(packageDir(skillId).resolve(packageFile(skillId)));
        } catch (Exception e) {
            throw new BizException("18005", "技能包读取失败");
        }
    }

    public String packageFileName(long skillId) {
        List<String> names = jdbc.queryForList(
                "SELECT package_file_name FROM mm_skill WHERE id=? AND workspace_id=?", String.class, skillId, ws());
        return names.isEmpty() || names.get(0) == null ? ("skill-" + skillId + ".zip") : names.get(0);
    }

    // ---------------------------------------------------------------- 分类

    public List<Map<String, Object>> categories() {
        return jdbc.queryForList(
                "SELECT id, name, parent_id AS parentId, gmt_create AS gmtCreate, version"
                        + " FROM as_category WHERE workspace_id=? AND deleted_at IS NULL ORDER BY parent_id, name", ws());
    }

    public Map<String, Object> createCategory(String name, Long parentId) {
        if (name == null || name.isBlank()) {
            throw new BizException("10001", "分类名称不能为空");
        }
        jdbc.update("INSERT INTO as_category (workspace_id, name, parent_id) VALUES (?,?,?)", ws(), name.trim(), parentId);
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM as_category WHERE workspace_id=?", Long.class, ws());
        return Map.of("id", id, "name", name.trim(), "parentId", parentId == null ? 0 : parentId);
    }

    public void updateCategory(long id, String name, Long parentId) {
        if (name != null && !name.isBlank()) {
            jdbc.update("UPDATE as_category SET name=? WHERE id=? AND workspace_id=?", name.trim(), id, ws());
        }
        if (parentId != null) {
            jdbc.update("UPDATE as_category SET parent_id=? WHERE id=? AND workspace_id=?", parentId, id, ws());
        }
    }

    public void deleteCategory(long id) {
        jdbc.update("UPDATE as_category SET deleted_at=NOW() WHERE id=? AND workspace_id=?", id, ws());
    }

    // ---------------------------------------------------------------- 工具

    private void require(long id) {
        if (jdbc.queryForList("SELECT 1 FROM mm_skill WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                id, ws()).isEmpty()) {
            throw new BizException("18001", "技能不存在");
        }
    }

    private Path packageDir(long skillId) {
        return root.resolve(String.valueOf(skillId));
    }

    private String packageFile(long skillId) {
        String fileName = packageFileName(skillId);
        return sanitize(fileName);
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
    }

    private String kindOf(String name) {
        String lower = name.toLowerCase();
        if (lower.matches(".*\\.(png|jpg|jpeg|gif|svg|webp)$")) {
            return "IMAGE";
        }
        if (lower.matches(".*\\.(md|txt|json|ya?ml|py|js|ts|tsx|jsx|sh|sql|html|css|java|go|rs)$")) {
            return "TEXT";
        }
        return "BINARY";
    }

    private boolean isText(byte[] bytes) {
        int limit = Math.min(bytes.length, 4096);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> frontMatter(String md) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = md.replace("\r\n", "\n");
        if (!text.startsWith("---")) {
            return out;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return out;
        }
        for (String line : text.substring(3, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                out.put(line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim().replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return out;
    }

    private String md5(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String strOr(Map<String, Object> m, String key, String fallback) {
        String v = str(m, key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private Long longOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }
}
