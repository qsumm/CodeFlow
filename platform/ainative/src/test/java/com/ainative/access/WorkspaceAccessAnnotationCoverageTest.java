package com.ainative.access;

import com.ainative.aiusage.AiUsageController;
import com.ainative.artifact.ArtifactController;
import com.ainative.branding.PlatformBrandingController;
import com.ainative.executor.ExecutorController;
import com.ainative.integration.ImChannelPublicController;
import com.ainative.integration.dingtalk.DingTalkBindingController;
import com.ainative.integration.feishu.FeishuCallbackController;
import com.ainative.im.PlatformImChannelConfigController;
import com.ainative.mcp.McpTokenController;
import com.ainative.setting.SystemSettingController;
import com.ainative.workitem.WorkitemController;
import com.ainative.workspace.WorkspaceController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAccessAnnotationCoverageTest {

    private static final String BASE_PACKAGE = "com.ainative";
    private static final String DEFAULT_ACTION = defaultAction();

    private static final Set<String> EXEMPT_CONTROLLERS = Set.of(
            "com.ainative.aiusage.DaemonTaskUsageController",
            "com.ainative.artifact.DaemonArtifactController",
            "com.ainative.artifact.WorkitemCliUploadController",
            "com.ainative.artifact.WorkitemCliDownloadController",
            "com.ainative.artifact.ScheduledTaskCliUploadController",
            // 派发级分享声明端点：daemon executor token 鉴权（DaemonUploadAuthenticator），
            // 与产物上传同一信任边界，无工作区上下文
            // 对外只读分享：AuthFilter 按不可猜 ainshare_ 令牌形态放行，无工作区上下文，
            // 端点自身只返回已显式暴露的产物
            "com.ainative.artifact.ShareController",
            // SSO 登录握手入口：/api/auth/sso/** 整体位于 AuthFilter 匿名白名单，
            // 与 AuthController 一样发生在工作区上下文建立之前，无访问阶梯可挂。
            "com.ainative.auth.federation.FederationAuthController",
            "com.ainative.controller.HealthCheckController",
            "com.ainative.controller.HelloWorldController",
            "com.ainative.debuglog.DebugLogUploadController",
            "com.ainative.dispatch.DaemonCheckpointController",
            // Runtime requests use DaemonUploadAuthenticator; external signals use a request-scoped JWT.
            // Neither channel carries a logged-in user's workspace access context.
            "com.ainative.dispatch.DaemonDispatchRequestController",
            "com.ainative.dispatch.DaemonRecoveryClaimController",
            "com.ainative.executor.DaemonExecutorEnvironmentController",
            "com.ainative.integration.dingtalk.HttpCallbackTransport",
            "com.ainative.mcp.McpController",
            "com.ainative.memory.store.DaemonMemoryController",
            "com.ainative.notification.NotificationController",
            "com.ainative.taskpackage.DaemonTaskPackageController",
            // Dispatch-scoped task data/identity endpoints authenticate with the executor token
            // through DaemonUploadAuthenticator and carry no logged-in workspace context.
            "com.ainative.taskdata.DaemonTaskDataController",
            "com.ainative.taskdata.DaemonWorkitemIdentityController",
            "com.ainative.user.AuthController",
            "com.ainative.workitem.DaemonCommentController"
    );

    private static final Set<String> EXEMPT_METHODS = Set.of(
            // Feishu callbacks authenticate through FeishuCallbackSecurity using the binding's
            // verification token and, when configured, signature; no user workspace context exists.
            "com.ainative.integration.feishu.FeishuCallbackController#callback("
                    + "java.lang.Long, java.lang.String, java.lang.String, java.lang.String, java.lang.String)",
            // Platform shared group-chat callback: the config id only locates the platform
            // GROUP_CHAT credentials and FeishuCallbackSecurity still verifies the signature;
            // external callbacks carry no logged-in user's workspace context.
            "com.ainative.integration.feishu.FeishuCallbackController#groupCallback("
                    + "java.lang.Long, java.lang.String, java.lang.String, java.lang.String, java.lang.String)",
            "com.ainative.branding.PlatformBrandingController#logo()",
            "com.ainative.branding.PlatformBrandingController#adminConfig()",
            "com.ainative.branding.PlatformBrandingController#publicConfig()",
            "com.ainative.branding.PlatformBrandingController#update("
                    + "com.ainative.branding.dto.UpdatePlatformBrandingRequest)",
            "com.ainative.branding.PlatformBrandingController#uploadLogo("
                    + "org.springframework.web.multipart.MultipartFile)",
            // Platform-admin management authorizes through SystemAdminService.requireSystemAdmin;
            // it is global, not workspace-scoped, so it stays outside the access ladder.
            "com.ainative.access.PlatformAdminController#list()",
            "com.ainative.access.PlatformAdminController#candidates(java.lang.String)",
            "com.ainative.access.PlatformAdminController#add("
                    + "com.ainative.access.dto.AddPlatformAdminRequest)",
            "com.ainative.access.PlatformAdminController#remove(java.lang.Long)",
            // 空间级看板是平台级/多空间只读聚合：授权由 SpaceDashboardService 按调用者可管理的空间集合
            // 判定（平台管理员 / owner / ADMIN 角色），与 token 绑定的当前工作空间无关，
            // 因此不进入工作区访问阶梯。
            "com.ainative.spacedashboard.SpaceDashboardController#overview("
                    + "java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)",
            // SSO 配置面与 PlatformAdminController 同源：SystemAdminService.requireSystemAdmin
            // 做全局平台管理员校验，不属于工作区访问阶梯。
            "com.ainative.auth.federation.FederationAdminController#listIdps()",
            "com.ainative.auth.federation.FederationAdminController#createIdp("
                    + "com.ainative.auth.federation.dto.CreateIdpRequest)",
            "com.ainative.auth.federation.FederationAdminController#updateIdp("
                    + "java.lang.Long, com.ainative.auth.federation.dto.UpdateIdpRequest)",
            "com.ainative.auth.federation.FederationAdminController#deleteIdp(java.lang.Long)",
            "com.ainative.auth.federation.FederationAdminController#listConnections()",
            "com.ainative.auth.federation.FederationAdminController#createConnection("
                    + "com.ainative.auth.federation.dto.CreateConnectionRequest)",
            "com.ainative.auth.federation.FederationAdminController#updateConnection("
                    + "java.lang.Long, com.ainative.auth.federation.dto.UpdateConnectionRequest)",
            "com.ainative.auth.federation.FederationAdminController#deleteConnection(java.lang.Long)",
            // SSO 只读概览：与 PlatformImChannelConfigController#list 同为平台级全局配置读取，
            // 面向所有已登录用户且不进入工作区访问阶梯；管理端点仍由 requireSystemAdmin 把关。
            "com.ainative.auth.federation.FederationOverviewController#overview()",
            // The platform-wide executor auto-upgrade switch is a global deployment setting read from
            // application.yml, so the workspace ladder cannot express it; reading is open to any
            // signed-in user and there is no write endpoint.
            "com.ainative.executor.PlatformRuntimeAutoUpdateController#view()",
            "com.ainative.im.PlatformImChannelConfigController#list()",
            "com.ainative.im.PlatformImChannelConfigController#updateFeishu("
                    + "com.ainative.im.dto.UpdateDingTalkChannelRequest)",
            "com.ainative.im.PlatformImChannelConfigController#updateDingTalk("
                    + "com.ainative.im.dto.UpdateDingTalkChannelRequest)",
            "com.ainative.integration.IntegrationCapabilityController#capabilities()",
            // Platform shared group-chat selection and configuration are global (one row for the
            // whole platform) and every endpoint authorizes through
            // SystemAdminService.requireSystemAdmin, so they stay outside the access ladder.
            "com.ainative.im.PlatformImChannelConfigController#selection()",
            "com.ainative.im.PlatformImChannelConfigController#selectProvider("
                    + "com.ainative.im.dto.SelectImChannelRequest)",
            "com.ainative.im.PlatformImChannelConfigController#groupChat(java.lang.String)",
            "com.ainative.im.PlatformImChannelConfigController#updateGroupChat("
                    + "java.lang.String, com.ainative.im.dto.UpdateGroupChatConfigRequest)",
            "com.ainative.mcp.McpTokenController#issue("
                    + "com.ainative.mcp.dto.CreateMcpTokenRequest)",
            "com.ainative.mcp.McpTokenController#list()",
            "com.ainative.mcp.McpTokenController#revoke(java.lang.Long)",
            "com.ainative.mcp.McpTokenController#tools()",
            "com.ainative.mcp.McpTokenController#platformSkills()",
            "com.ainative.workspace.WorkspaceController#create("
                    + "com.ainative.workspace.dto.CreateWorkspaceRequest)",
            "com.ainative.workspace.WorkspaceController#mine()",
            "com.ainative.workspace.WorkspaceController#switchWorkspace(java.lang.Long)",
            "com.ainative.workspace.WorkspaceController#listAllWorkspaces("
                    + "java.lang.String, int, int)",
            "com.ainative.workspace.WorkspaceController#submitAccessRequest("
                    + "java.lang.Long, com.ainative.workspace.dto.SubmitAccessRequestBody)",
            "com.ainative.workspace.WorkspaceController#cancelAccessRequest("
                    + "java.lang.Long, java.lang.Long)",
            // Workspace lifecycle endpoints are addressed by path id from the workspace-select
            // page, and after a delete the caller's token workspace no longer passes AuthFilter.
            // @RequireWorkspaceAccess resolves against that token workspace, so annotating restore
            // or the recycle bin would make them permanently unreachable (F5.2). WorkspaceService
            // enforces owner / active-ADMIN / platform-admin against the target workspace instead.
            "com.ainative.workspace.WorkspaceController#update("
                    + "java.lang.Long, com.ainative.workspace.dto.WorkspaceUpdateRequest)",
            "com.ainative.workspace.WorkspaceController#delete(java.lang.Long)",
            "com.ainative.workspace.WorkspaceController#recycleBin("
                    + "java.lang.String, int, int)",
            "com.ainative.workspace.WorkspaceController#restore("
                    + "java.lang.Long, com.ainative.workspace.dto.RestoreWorkspaceRequest)",
            "com.ainative.im.UserImIdentityController#list()",
            "com.ainative.im.UserImIdentityController#updateFeishu("
                    + "com.ainative.im.dto.UpdateUserImIdentityRequest)",
            "com.ainative.im.UserImIdentityController#testFeishu()",
            "com.ainative.im.UserImIdentityController#updateDingTalk("
                    + "com.ainative.im.dto.UpdateUserImIdentityRequest)",
            "com.ainative.im.UserImIdentityController#testDingTalk()",
            // Personal preferences are scoped to the authenticated user, not a workspace.
            // UserSettingController obtains userId only from the login context; DAO reads and
            // writes filter by that userId. Keep exemptions method-specific so new endpoints
            // still require an explicit access classification.
            "com.ainative.user.UserSettingController#list()",
            "com.ainative.user.UserSettingController#get(java.lang.String)",
            "com.ainative.user.UserSettingController#upsert("
                    + "java.lang.String, com.ainative.user.dto.UpsertUserSettingRequest)",
            "com.ainative.user.UserSettingController#delete(java.lang.String)",
            "com.ainative.user.UserAccountController#changePassword("
                    + "com.ainative.user.dto.ChangePasswordRequest)",
            "com.ainative.user.UserAccountController#getProfile()",
            "com.ainative.user.UserAccountController#updateProfile("
                    + "com.ainative.user.dto.UpdateProfileRequest)",
            "com.ainative.user.UserAccountController#initiateDeactivation("
                    + "com.ainative.user.dto.DeactivationRequest)",
            // 个人联邦身份（/api/users/me/federated-identities）：与 IM 身份绑定一样是
            // 登录用户私有数据，userId 只取自登录上下文，不经工作区。
            "com.ainative.auth.federation.FederatedIdentityController#list()",
            "com.ainative.auth.federation.FederatedIdentityController#claim("
                    + "com.ainative.auth.federation.dto.SsoTicketRequest)",
            "com.ainative.auth.federation.FederatedIdentityController#bindStart("
                    + "com.ainative.auth.federation.dto.SsoBindStartRequest)",
            "com.ainative.auth.federation.FederatedIdentityController#unbind(java.lang.Long)",
            // Platform-admin user management (like PlatformAdminController) authorizes through
            // SystemAdminService.requireSystemAdmin; it is global, not workspace-scoped, so every
            // endpoint stays outside the access ladder. Keep the exemptions method-specific so new
            // endpoints still require an explicit access classification.
            "com.ainative.user.UserAdminController#list("
                    + "java.lang.String, java.lang.String, java.lang.String, int, int)",
            "com.ainative.user.UserAdminController#detail(java.lang.Long)",
            "com.ainative.user.UserAdminController#updateProfile("
                    + "java.lang.Long, com.ainative.user.dto.UpdateProfileRequest)",
            "com.ainative.user.UserAdminController#resetPassword("
                    + "java.lang.Long, com.ainative.user.dto.AdminResetPasswordRequest)",
            "com.ainative.user.UserAdminController#delete("
                    + "java.lang.Long, com.ainative.user.dto.DeactivationRequest)",
            "com.ainative.user.UserAdminController#restore(java.lang.Long)",
            "com.ainative.user.UserAdminController#listWorkspaces(java.lang.Long)",
            "com.ainative.user.UserAdminController#addWorkspace("
                    + "java.lang.Long, com.ainative.user.dto.UserWorkspaceAddRequest)",
            "com.ainative.user.UserAdminController#updateWorkspaceLevel("
                    + "java.lang.Long, java.lang.Long, com.ainative.user.dto.UserWorkspaceLevelRequest)",
            "com.ainative.user.UserAdminController#removeWorkspace("
                    + "java.lang.Long, java.lang.Long)",
            "com.ainative.user.UserAdminController#listImIdentities(java.lang.Long)",
            "com.ainative.user.UserAdminController#updateImIdentity("
                    + "java.lang.Long, java.lang.String, com.ainative.im.dto.UpdateUserImIdentityRequest)",
            "com.ainative.user.UserAdminController#sendTestIm("
                    + "java.lang.Long, java.lang.String)"
    );

    @Test
    void everyWorkspaceEndpointResolvesAnExplicitAccessLevelAndAction() {
        List<String> unclassified = new ArrayList<>();
        List<String> invalidActions = new ArrayList<>();

        for (Class<?> controller : restControllers()) {
            if (isControllerExemptFromWorkspaceAccess(controller)) {
                continue;
            }
            RequireWorkspaceAccess classAccess =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequireWorkspaceAccess.class);
            for (Method method : requestMappedMethods(controller)) {
                if (EXEMPT_METHODS.contains(methodName(method))) {
                    continue;
                }
                RequireWorkspaceAccess methodAccess =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
                RequireWorkspaceAccess resolvedAccess = methodAccess != null ? methodAccess : classAccess;
                if (resolvedAccess == null) {
                    unclassified.add(methodName(method));
                } else if (resolvedAccess.action().isBlank()
                        || DEFAULT_ACTION.equals(resolvedAccess.action())) {
                    invalidActions.add(methodName(method));
                }
            }
        }

        assertTrue(unclassified.isEmpty(),
                () -> "Workspace endpoints without @RequireWorkspaceAccess:\n"
                        + String.join("\n", unclassified));
        assertTrue(invalidActions.isEmpty(),
                () -> "Workspace endpoints without an explicit access action:\n"
                        + String.join("\n", invalidActions));
    }

    @Test
    void everyAnnotatedControllerMapsToAWorkspaceDomain() {
        List<String> unmapped = new ArrayList<>();

        for (Class<?> controller : restControllers()) {
            RequireWorkspaceAccess classAccess =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequireWorkspaceAccess.class);
            boolean annotated = classAccess != null;
            for (Method method : requestMappedMethods(controller)) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class) != null) {
                    annotated = true;
                    break;
                }
            }
            if (annotated && WorkspaceDomain.of(controller) == null) {
                unmapped.add(controller.getName());
            }
        }

        assertTrue(unmapped.isEmpty(),
                () -> "Controllers without a WorkspaceDomain package mapping:\n"
                        + String.join("\n", unmapped));
    }

    @Test
    void everySourceActionHasExplicitPairedFiniteResourcesWithoutChangingExemptions() throws Exception {
        java.util.Properties zh = new java.util.Properties();
        java.util.Properties en = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("src/main/resources/i18n/messages_zh_CN.properties"))) { zh.load(reader); }
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("src/main/resources/i18n/messages_en_US.properties"))) { en.load(reader); }
        var sourceControllers = new java.util.LinkedHashSet<>(restControllers());
        // Spring skips this optional legacy API when disabled; its source actions still need resources.
        sourceControllers.add(com.ainative.memory.MemoryController.class);
        for (Class<?> controller : sourceControllers) {
            var annotations = new java.util.ArrayList<RequireWorkspaceAccess>();
            var type = controller.getDeclaredAnnotation(RequireWorkspaceAccess.class);
            if (type != null) annotations.add(type);
            for (Method method : declaredMethods(controller)) {
                var annotation = method.getDeclaredAnnotation(RequireWorkspaceAccess.class);
                if (annotation != null) annotations.add(annotation);
            }
            for (var annotation : annotations) {
                String key = annotation.actionKey();
                assertTrue(key.startsWith("access.action."), controller.getName());
                assertEquals(annotation.action(), zh.getProperty(key), key);
                assertNotNull(en.getProperty(key), key);
                assertTrue(!en.getProperty(key).isBlank(), key);
                assertTrue(!en.getProperty(key).matches(".*[\\u4e00-\\u9fff].*"), key);
            }
        }
        assertEquals(DEFAULT_ACTION, zh.getProperty("access.action.workspace_resources"));
        assertEquals("access Workspace resources", en.getProperty("access.action.workspace_resources"));
    }

    @Test
    void specialAccessLevelMatrixIsExplicitlyClassified() {
        // 执行器运维操作由 READ_WRITE 级 + 角色 EXECUTOR 只读域治理；
        // 令牌与启动命令携带凭证语义，仍收在管理员边界内。
        assertAccess(ExecutorController.class, "create", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "restart", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "update", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "updateAll", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "delete", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "updateLaunchConfig", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "updateModelUpgradePolicy", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ExecutorController.class, "getLaunchConfig", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "list", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "listAll", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "getModelCatalog", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "getModelUpgradePolicy", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "getToken", WorkspaceAccessLevel.ADMIN);
        assertAccess(ExecutorController.class, "buildLaunchCommand", WorkspaceAccessLevel.ADMIN);
        assertAccess(ExecutorController.class, "saveLaunchCommand", WorkspaceAccessLevel.ADMIN);
        assertAccess(ExecutorController.class, "applyLaunchCommand", WorkspaceAccessLevel.READ_WRITE);

        assertAccess(SystemSettingController.class, "listByGroup", WorkspaceAccessLevel.ADMIN);
        assertAccess(SystemSettingController.class, "updateGroup", WorkspaceAccessLevel.ADMIN);


        assertAccess(DingTalkBindingController.class, "list", WorkspaceAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "get", WorkspaceAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "create", WorkspaceAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "update", WorkspaceAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "delete", WorkspaceAccessLevel.ADMIN);

        assertAccess(AiUsageController.class, "listUsage", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(AiUsageController.class, "getQuota", WorkspaceAccessLevel.ADMIN);
        assertAccess(AiUsageController.class, "updateQuota", WorkspaceAccessLevel.ADMIN);

        assertAccess(ArtifactController.class, "listByWorkitem", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "listRequirementDocuments", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "download", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "preview", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "uploadRequirementDocuments", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ArtifactController.class, "deleteRequirementDocument", WorkspaceAccessLevel.READ_WRITE);

        assertAccess(WorkitemController.class, "create", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "transition", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "assign", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "updateContent", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "delete", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "listRecycled", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "restore", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "addComment", WorkspaceAccessLevel.READ_WRITE);

        assertAccess(WorkspaceController.class, "listMembers", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(WorkspaceController.class, "currentMembership", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(WorkspaceController.class, "searchMemberCandidates", WorkspaceAccessLevel.ADMIN);
        assertAccess(WorkspaceController.class, "addMember", WorkspaceAccessLevel.ADMIN);
        assertAccess(WorkspaceController.class, "removeMember", WorkspaceAccessLevel.ADMIN);
        assertAccess(WorkspaceController.class, "updateMemberRole", WorkspaceAccessLevel.ADMIN);
        assertAccess(WorkspaceController.class, "updateMemberIdentityTags", WorkspaceAccessLevel.ADMIN);
        assertAccess(WorkspaceController.class, "transferOwner", WorkspaceAccessLevel.ADMIN);

        assertAccess(ImChannelPublicController.class, "groupConfig", WorkspaceAccessLevel.READ_ONLY);
    }

    @Test
    void allowlistedChannelsStayOutsideWorkspaceAccessLadder() {
        List<String> annotatedExemptions = new ArrayList<>();

        for (Class<?> controller : restControllers()) {
            RequireWorkspaceAccess classAccess =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequireWorkspaceAccess.class);
            if (EXEMPT_CONTROLLERS.contains(controller.getName()) && classAccess != null) {
                annotatedExemptions.add(controller.getName());
            }
            for (Method method : requestMappedMethods(controller)) {
                boolean exempt = EXEMPT_CONTROLLERS.contains(controller.getName())
                        || EXEMPT_METHODS.contains(methodName(method));
                RequireWorkspaceAccess methodAccess =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
                if (exempt && (classAccess != null || methodAccess != null)) {
                    annotatedExemptions.add(methodName(method));
                }
            }
        }

        assertTrue(annotatedExemptions.isEmpty(),
                () -> "Allowlisted endpoints unexpectedly using @RequireWorkspaceAccess:\n"
                        + String.join("\n", annotatedExemptions));
    }

    @Test
    void globalPlatformManagementEndpointsStayOutsideWorkspaceAccessLadder() {
        assertExempt(PlatformBrandingController.class, "adminConfig");
        assertExempt(PlatformBrandingController.class, "update");
        assertExempt(PlatformBrandingController.class, "uploadLogo");
        assertExempt(PlatformImChannelConfigController.class, "list");
        assertExempt(PlatformImChannelConfigController.class, "updateDingTalk");
        assertExempt(PlatformImChannelConfigController.class, "selection");
        assertExempt(PlatformImChannelConfigController.class, "selectProvider");
        assertExempt(PlatformImChannelConfigController.class, "groupChat");
        assertExempt(PlatformImChannelConfigController.class, "updateGroupChat");
        assertExempt(PlatformAdminController.class, "list");
        assertExempt(PlatformAdminController.class, "candidates");
        assertExempt(PlatformAdminController.class, "add");
        assertExempt(PlatformAdminController.class, "remove");
    }

    @Test
    void workspaceLifecycleEndpointsStayOutsideWorkspaceAccessLadder() {
        assertExempt(WorkspaceController.class, "update");
        assertExempt(WorkspaceController.class, "delete");
        assertExempt(WorkspaceController.class, "recycleBin");
        assertExempt(WorkspaceController.class, "restore");
    }

    @Test
    void feishuPlatformCallbacksStayOutsideWorkspaceAccessLadder() {
        assertExempt(FeishuCallbackController.class, "callback");
        assertExempt(FeishuCallbackController.class, "groupCallback");
    }

    private boolean isControllerExemptFromWorkspaceAccess(Class<?> controller) {
        return EXEMPT_CONTROLLERS.contains(controller.getName());
    }

    private static String defaultAction() {
        try {
            return (String) RequireWorkspaceAccess.class
                    .getDeclaredMethod("action")
                    .getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Could not inspect @RequireWorkspaceAccess action default", e);
        }
    }

    private void assertAccess(
            Class<?> controller, String methodName, WorkspaceAccessLevel expectedLevel) {
        Method method = mappedMethod(controller, methodName);
        RequireWorkspaceAccess methodAccess =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        RequireWorkspaceAccess access = methodAccess != null
                ? methodAccess
                : AnnotatedElementUtils.findMergedAnnotation(controller, RequireWorkspaceAccess.class);
        assertNotNull(access, () -> methodName(method) + " should require workspace access");
        assertEquals(expectedLevel, access.value(), methodName(method));
    }

    private void assertExempt(Class<?> controller, String methodName) {
        Method method = mappedMethod(controller, methodName);
        assertTrue(EXEMPT_METHODS.contains(methodName(method)),
                () -> methodName(method) + " should be explicitly allowlisted");
        assertTrue(AnnotatedElementUtils.findMergedAnnotation(
                        method, RequireWorkspaceAccess.class) == null,
                () -> methodName(method) + " should stay outside workspace access");
    }

    private Method mappedMethod(Class<?> controller, String name) {
        List<Method> matches = requestMappedMethods(controller).stream()
                .filter(method -> method.getName().equals(name))
                .toList();
        assertEquals(1, matches.size(),
                () -> controller.getName() + "#" + name + " should identify one mapped method");
        return matches.get(0);
    }

    private List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(this::loadClass)
                // Inspect production controllers, not nested REST fixtures from test-classes.
                .filter(type -> type.getProtectionDomain().getCodeSource().getLocation().equals(
                        RequireWorkspaceAccess.class.getProtectionDomain().getCodeSource().getLocation()))
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Could not load REST controller " + className, e);
        }
    }

    private List<Method> requestMappedMethods(Class<?> controller) {
        return declaredMethods(controller).stream()
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }

    private List<Method> declaredMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .sorted(Comparator.comparing(this::methodName))
                .toList();
    }

    private String methodName(Method method) {
        String parameterTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(", "));
        return method.getDeclaringClass().getName()
                + "#" + method.getName() + "(" + parameterTypes + ")";
    }
}
