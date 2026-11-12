package com.ainative.agent;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformIntelligenceControllerTest {
    @AfterEach
    void cleanup() { RequestContext.destroy(); }

    @Test
    void statusUsesAuthenticatedWorkspaceAndRequiresReadAccess() {
        var service = mock(PlatformIntelligenceService.class);
        var status = new PlatformIntelligenceService.CapabilityStatus(null,
                PlatformIntelligenceService.Availability.NOT_CONFIGURED);
        when(service.getStatus(7L)).thenReturn(status);
        RequestContext.get().setCurrentWorkspaceId(7L);
        assertEquals(status, new PlatformIntelligenceController(service).status().getData());
        assertEquals(WorkspaceAccessLevel.READ_ONLY, PlatformIntelligenceController.class
                .getAnnotation(RequireWorkspaceAccess.class).value());
    }

    @Test
    void missingWorkspaceIsRejected() {
        var service = mock(PlatformIntelligenceService.class);
        assertThrows(IllegalStateException.class, () -> new PlatformIntelligenceController(service).status());
        verifyNoInteractions(service);
    }
}
