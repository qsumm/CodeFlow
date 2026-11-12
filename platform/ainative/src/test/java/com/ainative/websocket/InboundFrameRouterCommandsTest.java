package com.ainative.websocket;

import com.ainative.artifact.ArtifactService;
import com.ainative.conversation.ConversationCommandsService;
import com.ainative.dispatch.DispatchPauseService;
import com.ainative.dispatch.DispatchService;
import com.ainative.dispatch.HandoffService;
import com.ainative.guidance.GuidanceService;
import com.ainative.guidance.InteractionWorkflowService;
import com.ainative.skill.RuntimeMcpConnectionTestService;
import javax.websocket.Session;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class InboundFrameRouterCommandsTest {

    private final DispatchService dispatchService = mock(DispatchService.class);
    private final ArtifactService artifactService = mock(ArtifactService.class);
    private final PresenceManager presenceManager = mock(PresenceManager.class);
    private final HandoffService handoffService = mock(HandoffService.class);
    private final DispatchDrainScheduler drainScheduler = mock(DispatchDrainScheduler.class);
    private final DispatchPauseService pauseService = mock(DispatchPauseService.class);
    private final GuidanceService guidanceService = mock(GuidanceService.class);
    private final InteractionWorkflowService interactionWorkflowService = mock(InteractionWorkflowService.class);
    private final RuntimeMcpConnectionTestService runtimeMcpConnectionTestService =
            mock(RuntimeMcpConnectionTestService.class);
    private final ConversationCommandsService commandsService = mock(ConversationCommandsService.class);

    private final InboundFrameRouter router = new InboundFrameRouter(dispatchService, artifactService,
            presenceManager, handoffService, drainScheduler, pauseService, guidanceService,
            interactionWorkflowService, null, null, runtimeMcpConnectionTestService);

    @Test
    void routesCommandsResultToService() {
        router.setConversationCommandsService(commandsService);
        ExecutorSession es = new ExecutorSession(9L, 3L, 1L, mock(Session.class));

        String msg = "{\"type\":\"CONVERSATION_COMMANDS_RESULT\",\"conversationId\":42,"
                + "\"status\":\"OK\",\"commands\":\"{\\\"availableCommands\\\":[]}\",\"error\":\"\"}";
        router.route(es, msg);

        // executorId 取自回连会话（es.getExecutorId()），不是帧内容：服务端据此校验归属
        verify(commandsService).onResult(1L, 9L, 42L, "OK", "{\"availableCommands\":[]}", "");
    }
}
