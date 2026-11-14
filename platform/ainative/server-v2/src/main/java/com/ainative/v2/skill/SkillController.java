package com.ainative.v2.skill;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.PageResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 技能与分类接口。 */
@RestController
public class SkillController {
    public record CategoryRequest(Long categoryId) {}
    public record BatchCategoryRequest(List<Long> skillIds, Long categoryId) {}
    public record CategoryBody(String name, Long parentId) {}

    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 技能

    @GetMapping("/api/skills")
    public ApiResult<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.list(keyword, categoryId, type, page, size));
    }

    @PostMapping("/api/skills")
    public ApiResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(service.create(body, RequestContext.requireUserId()));
    }

    @GetMapping("/api/skills/{id}")
    public ApiResult<Map<String, Object>> get(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.get(id));
    }

    @PutMapping("/api/skills/{id}")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResult.ok(service.update(id, body, RequestContext.requireUserId()));
    }

    @DeleteMapping("/api/skills/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.delete(id);
        return ApiResult.ok(null);
    }

    @PutMapping("/api/skills/{id}/category")
    public ApiResult<Void> setCategory(@PathVariable long id, @RequestBody CategoryRequest req) {
        RequestContext.requireUserId();
        service.setCategory(id, req.categoryId());
        return ApiResult.ok(null);
    }

    @PostMapping("/api/skills/category/batch")
    public ApiResult<List<Map<String, Object>>> batchCategory(@RequestBody BatchCategoryRequest req) {
        RequestContext.requireUserId();
        int updated = service.batchCategory(req.skillIds() == null ? List.of() : req.skillIds(), req.categoryId());
        return ApiResult.ok(List.of(Map.of("updated", updated)));
    }

    // ---------------------------------------------------------------- 包

    @PostMapping("/api/skills/package/inspect")
    public ApiResult<Map<String, Object>> inspect(@RequestParam("file") MultipartFile file) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.inspectPackage(file));
    }

    @PostMapping("/api/skills/{id}/package")
    public ApiResult<Map<String, Object>> upload(@PathVariable long id, @RequestParam("file") MultipartFile file) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.uploadPackage(id, file));
    }

    @GetMapping("/api/skills/{id}/package/files")
    public ApiResult<Map<String, Object>> files(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listPackageFiles(id));
    }

    @GetMapping("/api/skills/{id}/package/file")
    public ApiResult<Map<String, Object>> file(@PathVariable long id, @RequestParam String path) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.readPackageFile(id, path));
    }

    /** 下载返回裸字节(前端用 fetch 取 blob),不是 Result 信封。 */
    @GetMapping("/api/skills/{id}/package/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        RequestContext.requireUserId();
        byte[] bytes = service.packageBytes(id);
        String name = URLEncoder.encode(service.packageFileName(id), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
                .body(bytes);
    }

    /** 连接测试依赖在线运行器;无运行器时返回明确原因,不伪造成功。 */
    @PostMapping("/api/skills/{id}/connection-test")
    public ApiResult<Map<String, Object>> connectionTest(@PathVariable long id) {
        RequestContext.requireUserId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("message", "当前没有在线运行器,无法执行连接测试");
        out.put("ownedFailureCode", "RUNTIME_UNAVAILABLE");
        return ApiResult.ok(out);
    }

    // ---------------------------------------------------------------- 分类

    @GetMapping("/api/categories")
    public ApiResult<List<Map<String, Object>>> categories() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.categories());
    }

    @PostMapping("/api/categories")
    public ApiResult<Map<String, Object>> createCategory(@RequestBody CategoryBody body) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createCategory(body.name(), body.parentId()));
    }

    @PutMapping("/api/categories/{id}")
    public ApiResult<Void> updateCategory(@PathVariable long id, @RequestBody CategoryBody body) {
        RequestContext.requireUserId();
        service.updateCategory(id, body.name(), body.parentId());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/api/categories/{id}")
    public ApiResult<Void> deleteCategory(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteCategory(id);
        return ApiResult.ok(null);
    }
}
