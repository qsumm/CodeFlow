package com.ainative.backup;

import com.ainative.access.WorkspaceAccessAspect;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.WorkspaceAccessDeniedException;
import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBackupControllerTest {
    @AfterEach void cleanup() { RequestContext.destroy(); }

    @Test void allEndpointsDenyNonAdminsBeforeAccessingData() {
        var service = mock(ProjectBackupService.class);
        var controller = proxy(service);
        for (var level : new WorkspaceAccessLevel[]{WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.READ_WRITE}) {
            RequestContext.get().setUserId(1L);
            RequestContext.get().setCurrentWorkspaceId(2L);
            RequestContext.get().setWorkspaceAccessLevel(level);
            assertThrows(WorkspaceAccessDeniedException.class, controller::create);
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.list(1, 20));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.download("id"));
        }
        verifyNoInteractions(service);
    }

    @Test void adminUsesOnlyAuthenticatedWorkspaceAndBoundedPagination() {
        var service = mock(ProjectBackupService.class);
        var controller = proxy(service);
        RequestContext.get().setUserId(9L);
        RequestContext.get().setCurrentWorkspaceId(2L);
        RequestContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        controller.create();
        controller.list(-1, 999);
        when(service.download(2, "id", 9L)).thenReturn("https://example.com/backup.zip");
        controller.download("id");
        verify(service).create(2, 9);
        verify(service).list(2, 1, 100);
        verify(service).download(2, "id", 9L);
    }

    private ProjectBackupController proxy(ProjectBackupService service) {
        var factory = new AspectJProxyFactory(new ProjectBackupController(service));
        factory.addAspect(new WorkspaceAccessAspect());
        return factory.getProxy();
    }
}
