package com.ainative.agent;

import com.ainative.context.RequestContext;
import com.ainative.squad.SquadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class AgentControllerTenantScopeTest {

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void listVersionsPassesCurrentWorkspaceToService() {
        AgentService agentService = mock(AgentService.class);
        AgentController controller = new AgentController(agentService, mock(SquadService.class));
        RequestContext.get().setCurrentWorkspaceId(100L);
        when(agentService.listVersions(10L, 100L)).thenReturn(List.of());

        controller.listVersions(10L);

        verify(agentService).listVersions(10L, 100L);
        verifyNoMoreInteractions(agentService);
    }
}
