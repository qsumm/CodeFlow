package com.ainative.agent;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.agent.dto.PlatformAgentStatusVO;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAgentControllerTest {

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void statusReturnsServiceSnapshotForCurrentWorkspace() {
        PlatformAgentStatusService statusService = mock(PlatformAgentStatusService.class);
        PlatformAgentController controller = new PlatformAgentController(statusService);
        PlatformAgentStatusVO snapshot = new PlatformAgentStatusVO();
        snapshot.setState(PlatformAgentStatusVO.STATE_OK);
        when(statusService.getStatus(100L)).thenReturn(snapshot);
        RequestContext.get().setCurrentWorkspaceId(100L);

        assertSame(snapshot, controller.status().getData());

        verify(statusService).getStatus(100L);
    }

    @Test
    void statusRejectsRequestWithoutWorkspaceContext() {
        PlatformAgentStatusService statusService = mock(PlatformAgentStatusService.class);
        PlatformAgentController controller = new PlatformAgentController(statusService);

        BizException ex = assertThrows(BizException.class, controller::status);

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), ex.getCode());
    }

    @Test
    void statusRouteUsesClassLevelReadOnlyWorkspaceAccess() throws Exception {
        assertArrayEquals(new String[]{"/api/platform-agent"},
                PlatformAgentController.class.getAnnotation(RequestMapping.class).value());
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                PlatformAgentController.class.getAnnotation(RequireWorkspaceAccess.class).value());

        Method method = PlatformAgentController.class.getDeclaredMethod("status");
        assertArrayEquals(new String[]{"/status"}, method.getAnnotation(GetMapping.class).value());
    }
}
