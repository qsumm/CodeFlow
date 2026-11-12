package com.ainative.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestContextTest {
    @AfterEach
    void tearDown() { RequestContext.destroy(); }

    @Test
    void holdsUserAndWorkspace() {
        RequestContext.get().setUserId(7L);
        RequestContext.get().setCurrentWorkspaceId(9L);
        assertEquals(7L, RequestContext.get().getUserId());
        assertEquals(9L, RequestContext.get().getCurrentWorkspaceId());
    }

    @Test
    void clearedAfterDestroy() {
        RequestContext.get().setUserId(7L);
        RequestContext.destroy();
        assertNull(RequestContext.get().getUserId());
    }
}
