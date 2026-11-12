package com.ainative.im;

import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessAspect;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.im.dto.UpdateUserImIdentityRequest;
import com.ainative.im.dto.UserImIdentityVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserImIdentityControllerTest {

    @AfterEach
    void cleanup() {
        RequestContext.destroy();
    }

    @Test
    void classDoesNotRequireWorkspaceAccessSoPersonalListWorksWithoutCurrentWorkspace() throws Exception {
        RequireWorkspaceAccess classAccess =
                UserImIdentityController.class.getAnnotation(RequireWorkspaceAccess.class);
        assertNull(classAccess,
                "class-level @RequireWorkspaceAccess must be removed so /profile/settings "
                        + "IM tab remains usable when currentWorkspaceId is null or stale (design §10.1)");
    }

    @Test
    void listReturnsOkWhenCurrentWorkspaceIdIsNull() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = new UserImIdentityController(service);
        RequestContext.get().setUserId(200L);
        when(service.list(200L)).thenReturn(List.of());

        Result<List<UserImIdentityVO>> result = controller.list();

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        verify(service).list(200L);
    }

    @Test
    void updateDingTalkUsesCurrentUserWithoutWorkspaceDependency() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = proxiedController(service);
        RequestContext.get().setUserId(200L);
        RequestContext.get().setCurrentWorkspaceId(null);
        UpdateUserImIdentityRequest request = new UpdateUserImIdentityRequest();
        request.setExternalUserId("220791");
        UserImIdentityVO saved = new UserImIdentityVO();
        saved.setProvider("DINGTALK");
        saved.setExternalUserId("220791");
        when(service.update(200L, "DINGTALK", "220791")).thenReturn(saved);

        Result<UserImIdentityVO> result = controller.updateDingTalk(request);

        assertTrue(result.isSuccess());
        assertEquals("220791", result.getData().getExternalUserId());
        verify(service).update(200L, "DINGTALK", "220791");
    }

    @Test
    void testEndpointUsesCurrentUserWithoutWorkspaceDependency() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = proxiedController(service);
        RequestContext.get().setUserId(200L);
        RequestContext.get().setCurrentWorkspaceId(null);

        Result<Void> result = controller.testDingTalk();

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        verify(service).sendTest(200L, "DINGTALK");
    }

    private UserImIdentityController proxiedController(UserImIdentityService service) {
        AspectJProxyFactory factory =
                new AspectJProxyFactory(new UserImIdentityController(service));
        factory.addAspect(new WorkspaceAccessAspect());
        return factory.getProxy();
    }
}
