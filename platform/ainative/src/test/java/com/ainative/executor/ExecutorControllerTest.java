package com.ainative.executor;

import com.ainative.json.JSONObject;
import com.ainative.common.error.BizException;
import com.ainative.context.RequestContext;
import com.ainative.executor.dto.ExecutorUpdateAllResultVO;
import com.ainative.executor.dto.ExecutorUpdateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutorControllerTest {

    private final ExecutorRestartService restartService = mock(ExecutorRestartService.class);
    private final ExecutorUpdateService updateService = mock(ExecutorUpdateService.class);

    @AfterEach
    void cleanup() {
        RequestContext.destroy();
    }

    @Test
    void creationReturnsTheRecordButKeepsThePlaintextTokenForAdministratorsOnly() {
        var executorService = mock(ExecutorService.class);
        when(executorService.create(eq(9L), any(), eq(100L), eq(10000L))).thenAnswer(invocation -> {
            var fresh = new com.ainative.executor.dto.IssuedExecutorVO();
            fresh.setId(7L);
            fresh.setToken("exec_secret_token");
            return fresh;
        });
        ExecutorController controller = new ExecutorController(executorService,
                mock(ProviderModelCatalogService.class), mock(ExecutorLaunchConfigService.class),
                mock(ExecutorLaunchCommandService.class), mock(com.ainative.squad.SquadService.class));
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        RequestContext.get().setWorkspaceAccessLevel(
                com.ainative.access.WorkspaceAccessLevel.READ_WRITE);
        assertNull(controller.create(9L, new com.ainative.executor.dto.CreateExecutorRequest())
                .getData().getToken());

        // 管理员创建仍可一次性拿到明文凭据。
        RequestContext.get().setWorkspaceAccessLevel(
                com.ainative.access.WorkspaceAccessLevel.ADMIN);
        assertEquals("exec_secret_token",
                controller.create(9L, new com.ainative.executor.dto.CreateExecutorRequest())
                        .getData().getToken());
    }

    @Test
    void launchConfigReadUsesTheSideEffectFreeView() {
        var configService = mock(ExecutorLaunchConfigService.class);
        var stored = new com.ainative.executor.dto.ExecutorLaunchConfigVO();
        when(configService.readStoredConfig(7L, 100L)).thenReturn(stored);
        ExecutorController controller = new ExecutorController(mock(ExecutorService.class),
                mock(ProviderModelCatalogService.class), configService,
                mock(ExecutorLaunchCommandService.class), mock(com.ainative.squad.SquadService.class));
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(stored, controller.getLaunchConfig(7L).getData());
        verify(configService).readStoredConfig(7L, 100L);
    }

    private ExecutorController controller() {
        ExecutorController controller = new ExecutorController(mock(ExecutorService.class),
                mock(ProviderModelCatalogService.class), mock(ExecutorLaunchConfigService.class),
                mock(ExecutorLaunchCommandService.class), mock(com.ainative.squad.SquadService.class));
        controller.setRestartService(restartService);
        controller.setUpdateService(updateService);
        return controller;
    }

    /** A restart can carry the runtime upgrade with it, and that choice belongs to the caller. */
    @Test
    void previewUsesTheCurrentWorkspaceAndDoesNotRequestARestart() {
        var commands = mock(ExecutorLaunchCommandService.class);
        var request = new com.ainative.executor.dto.ExecutorLaunchCommandPreviewRequest();
        var preview = new com.ainative.executor.dto.ExecutorLaunchCommandVO();
        when(commands.previewForExecutor(7L, 100L, request)).thenReturn(preview);
        RequestContext.get().setCurrentWorkspaceId(100L);
        var controller = new ExecutorController(mock(ExecutorService.class), mock(ProviderModelCatalogService.class),
                mock(ExecutorLaunchConfigService.class), commands, mock(com.ainative.squad.SquadService.class));
        assertSame(preview, controller.previewLaunchCommand(7L, request).getData());
        verify(commands).previewForExecutor(7L, 100L, request);
        verifyNoInteractions(restartService);
    }

    @Test
    void restartForwardsWhetherTheRuntimeShouldBeUpgradedToo() {
        JSONObject ack = new JSONObject();
        when(restartService.request(7L, 100L, 10000L, true)).thenReturn(ack);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(ack, controller().restart(7L, new ExecutorController.RestartRequest(true)).getData());

        verify(restartService).request(7L, 100L, 10000L, true);
    }

    @Test
    void restartWithoutABodyRestartsTheProcessTheOperatorAlreadyHas() {
        JSONObject ack = new JSONObject();
        when(restartService.request(7L, 100L, 10000L, false)).thenReturn(ack);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(ack, controller().restart(7L, null).getData());

        // Reading a missing body as "upgrade too" would change the runtime version as a side effect of
        // a plain restart.
        verify(restartService).request(7L, 100L, 10000L, false);
    }

    @Test
    void updateUpgradesOneExecutorInsideTheCallersWorkspace() {
        ExecutorUpdateVO vo = new ExecutorUpdateVO();
        when(updateService.updateOne(7L, 100L, 10000L)).thenReturn(vo);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(vo, controller().update(7L).getData());

        verify(updateService).updateOne(7L, 100L, 10000L);
    }

    /** 一键全量更新 means every executor the operator can currently see, so the filter is passed through. */
    @Test
    void updateAllKeepsTheSquadFilterThePanelIsShowing() {
        ExecutorUpdateAllResultVO result = new ExecutorUpdateAllResultVO();
        when(updateService.updateAll(100L, List.of(3L, 4L), 10000L)).thenReturn(result);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(result, controller().updateAll(List.of(3L, 4L)).getData());

        verify(updateService).updateAll(100L, List.of(3L, 4L), 10000L);
    }

    @Test
    void updateAllWithoutAFilterCoversTheWholeWorkspace() {
        ExecutorUpdateAllResultVO result = new ExecutorUpdateAllResultVO();
        when(updateService.updateAll(100L, null, 10000L)).thenReturn(result);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        assertSame(result, controller().updateAll(null).getData());

        verify(updateService).updateAll(100L, null, 10000L);
    }

    @Test
    void anUpgradeWithoutAWorkspaceContextIsRefusedBeforeItReachesTheService() {
        RequestContext.get().setUserId(10000L);

        assertThrows(BizException.class, () -> controller().update(7L));

        verifyNoInteractions(updateService);
    }

    @Test
    void batchProvisionIssuesTokensAndCommandsForAdminsOnly() {
        var executorService = mock(ExecutorService.class);
        var squadService = mock(com.ainative.squad.SquadService.class);
        var launchCommandService = mock(ExecutorLaunchCommandService.class);
        var members = new java.util.LinkedHashMap<Long, String>();
        members.put(31L, "全栈开发");
        when(squadService.memberAgentNames(55L, 100L)).thenReturn(members);
        when(executorService.provisionForAgents(any(), any(), eq(100L), eq(10000L))).thenAnswer(invocation -> {
            var issued = new com.ainative.executor.dto.IssuedExecutorVO();
            issued.setId(7L);
            issued.setToken("exec_secret_token");
            issued.setClientKind("QODER_CLI");
            issued.setMemoryMode("platform");
            var item = new com.ainative.executor.dto.ProvisionItemVO();
            item.setAgentId(31L);
            item.setAgentName("全栈开发");
            item.setStatus(com.ainative.executor.dto.ProvisionItemVO.CREATED);
            item.setExecutor(issued);
            return List.of(item);
        });
        when(launchCommandService.build(eq("exec_secret_token"), eq(7L), eq("QODER_CLI"), eq("platform"),
                any(), any(), any(), eq("posix"), eq(false), any())).thenReturn(
                new com.ainative.executor.dto.ExecutorLaunchCommandVO() {{ setCommand("npx -y ymai-codeflow@x connect --token exec_secret_token"); }});
        ExecutorController controller = new ExecutorController(executorService,
                mock(ProviderModelCatalogService.class), mock(ExecutorLaunchConfigService.class),
                launchCommandService, squadService);
        RequestContext.get().setCurrentWorkspaceId(100L);
        RequestContext.get().setUserId(10000L);

        var req = new com.ainative.executor.dto.BatchProvisionSquadExecutorsRequest();
        req.setAgentIds(List.of(31L));

        RequestContext.get().setWorkspaceAccessLevel(com.ainative.access.WorkspaceAccessLevel.READ_WRITE);
        var readOnly = controller.provisionForSquad(55L, req).getData();
        assertEquals("CREATED", readOnly.getItems().get(0).getStatus());
        assertNull(readOnly.getItems().get(0).getCommand());
        assertNull(readOnly.getItems().get(0).getExecutor().getToken());

        RequestContext.get().setWorkspaceAccessLevel(com.ainative.access.WorkspaceAccessLevel.ADMIN);
        var admin = controller.provisionForSquad(55L, req).getData();
        assertEquals("npx -y ymai-codeflow@x connect --token exec_secret_token",
                admin.getItems().get(0).getCommand());
        assertEquals("exec_secret_token", admin.getItems().get(0).getExecutor().getToken());
    }
}
