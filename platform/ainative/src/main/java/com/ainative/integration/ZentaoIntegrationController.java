package com.ainative.integration;

import com.ainative.common.result.Result;
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.provider.ExternalProject;
import com.ainative.integration.provider.PageResult;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.security.crypto.SecretCrypto;
import com.ainative.integration.zentao.ZentaoClient;
import com.ainative.integration.zentao.ZentaoWorkitemProvider;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.context.RequestContext;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 禅道绑定 REST 入口(与平台集成其余入口同构,前端禅道表单消费)。
 * 复用 external_project_binding 存储(provider=ZENTAO);凭证 SecretCrypto 加密。
 */
@RestController
@RequestMapping("/api/integrations/zentao")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理禅道集成", actionKey = "access.action.manage_zentao_integration")
public class ZentaoIntegrationController {

    private final ZentaoBindingService bindingService;
    private final ZentaoWorkitemProvider workitemProvider;
    private final SecretCrypto secretCrypto;
    private final ZentaoClient client;

    public ZentaoIntegrationController(ZentaoBindingService bindingService,
                                       ZentaoWorkitemProvider workitemProvider,
                                       SecretCrypto secretCrypto, ZentaoClient client) {
        this.bindingService = bindingService;
        this.workitemProvider = workitemProvider;
        this.secretCrypto = secretCrypto;
        this.client = client;
    }

    private ExternalPlatformConfig configOf(ZentaoBindingService.ZentaoBindingRequest req) {
        return new ExternalPlatformConfig(req.baseUrl(), req.account(), req.password(), null);
    }

    /** 测试连接:换 token + 拉产品列表。 */
    @PostMapping("/bindings/test")
    public Result<Map<String, Object>> testConnection(@RequestBody ZentaoBindingService.ZentaoBindingRequest req) {
        try {
            int products = client.listProducts(req.baseUrl(), req.account(), req.password()).size();
            return Result.ok(Map.of("success", true, "message", "连接成功,可见产品 " + products + " 个", "checks", List.of("token", "products")));
        } catch (RuntimeException e) {
            return Result.ok(Map.of("success", false, "message",
                    e.getMessage() == null ? "连接失败" : e.getMessage()));
        }
    }

    /** 产品搜索:禅道 /products,供绑定表单下拉。 */
    @PostMapping("/projects/search")
    public Result<List<ExternalProject>> searchProjects(@RequestBody ZentaoBindingService.ZentaoBindingRequest req) {
        com.ainative.json.JSONArray products = client.listProducts(req.baseUrl(), req.account(), req.password());
        List<ExternalProject> projects = new java.util.ArrayList<>();
        for (int i = 0; i < products.size(); i++) {
            com.ainative.json.JSONObject p = products.getJSONObject(i);
            ExternalProject project = new ExternalProject();
            project.setExternalId(p.getString("id"));
            project.setName(p.getString("name"));
            project.setRawJson(p.toJSONString());
            projects.add(project);
        }
        return Result.ok(projects);
    }

    @PostMapping("/bindings")
    public Result<ExternalProjectBindingDO> createBinding(@RequestBody ZentaoBindingService.ZentaoBindingRequest req) {
        return Result.ok(bindingService.createBinding(req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/bindings")
    public Result<List<ExternalProjectBindingDO>> listBindings() {
        return Result.ok(bindingService.listBindings(currentWorkspaceId()));
    }

    @PostMapping("/bindings/{bindingId}/sync-now")
    public Result<Integer> syncNow(@PathVariable Long bindingId) {
        ExternalProjectBindingDO binding = bindingService.requireBinding(bindingId, currentWorkspaceId());
        var config = new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(),
                secretCrypto.decrypt(binding.getCredentialRef()), null);
        PageResult<com.ainative.integration.provider.ExternalWorkitemSummary> page =
                workitemProvider.searchProject(config, binding.getExternalProjectId(), null, null);
        return Result.ok(page.getItems() == null ? 0 : page.getItems().size());
    }

    private long currentUserId() {
        Long uid = RequestContext.get().getUserId();
        if (uid == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return workspaceId;
    }
}
