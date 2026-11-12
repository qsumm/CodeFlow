package com.ainative.guidance;

import com.ainative.dispatch.DispatchService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GuidanceDispatchQueuedListenerTest {

    @Test
    void startsTheSideDispatchOnlyAfterTheGuidanceTransactionCommits() {
        DispatchService dispatchService = mock(DispatchService.class);
        GuidanceDispatchQueuedListener listener = new GuidanceDispatchQueuedListener(dispatchService);

        listener.onQueued(new GuidanceDispatchQueuedEvent(100L, 92L));

        verify(dispatchService).runPending(92L);
    }
}
