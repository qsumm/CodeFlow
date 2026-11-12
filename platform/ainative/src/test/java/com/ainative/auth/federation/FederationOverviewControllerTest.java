package com.ainative.auth.federation;

import com.ainative.auth.federation.dto.FederationOverviewVO;
import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederationOverviewControllerTest {
    private final FederationRegistryService registryService = mock(FederationRegistryService.class);
    private final FederationOverviewController controller = new FederationOverviewController(registryService);

    @AfterEach
    void cleanup() {
        RequestContext.destroy();
    }

    @Test
    void overviewIsReadableByANormalAuthenticatedUserWithoutAdminChecks() {
        RequestContext.get().setUserId(20001L);
        FederationOverviewVO overview = new FederationOverviewVO();
        overview.setIdps(List.of());
        overview.setConnections(List.of());
        when(registryService.overview()).thenReturn(overview);

        assertSame(overview, controller.overview().getData());
        verify(registryService).overview();
    }
}
