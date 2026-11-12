package com.ainative.mcp;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.common.error.BizException;
import com.ainative.context.RequestContext;
import com.ainative.mcp.dto.CreateMcpTokenRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpTokenControllerPermissionTest {

    private final McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
    private final McpToolService toolService = mock(McpToolService.class);
    private final PlatformSkillCatalog platformSkillCatalog = mock(PlatformSkillCatalog.class);
    private final McpTokenController controller =
            new McpTokenController(tokenService, toolService, platformSkillCatalog);

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void createRequestNoLongerCarriesAnAccessLevel() {
        assertFalse(Arrays.stream(CreateMcpTokenRequest.class.getDeclaredFields())
                .anyMatch(field -> "accessLevel".equals(field.getName())));
    }

    @Test
    void issueForwardsOnlyNameAndOwnerWithoutAnyWorkspace() {
        CreateMcpTokenRequest request = new CreateMcpTokenRequest();
        request.setName("local-codex");
        RequestContext.get().setUserId(7L);

        controller.issue(request);

        verify(tokenService).issue("local-codex", 7L);
    }

    @Test
    void personalEndpointsWorkWithoutASelectedWorkspace() {
        RequestContext.get().setUserId(7L);

        controller.issue(null);
        controller.list();
        controller.revoke(9L);
        controller.tools();
        controller.platformSkills();

        verify(tokenService).issue(null, 7L);
        verify(tokenService).list(7L);
        verify(tokenService).revoke(9L, 7L);
        verify(toolService).listPersonalTools();
        verify(platformSkillCatalog).list();
    }

    @Test
    void personalEndpointsStillRequireAnAuthenticatedUser() {
        BizException thrown = assertThrows(BizException.class, controller::list);

        assertEquals("10401", thrown.getCode());
    }

    @Test
    void personalTokenEndpointsStayOutsideWorkspaceAccessLadder() throws Exception {
        assertExempt(McpTokenController.class.getMethod("issue", CreateMcpTokenRequest.class));
        assertExempt(McpTokenController.class.getMethod("list"));
        assertExempt(McpTokenController.class.getMethod("revoke", Long.class));
        assertExempt(McpTokenController.class.getMethod("tools"));
        assertExempt(McpTokenController.class.getMethod("platformSkills"));
    }

    private void assertExempt(Method method) {
        assertFalse(AnnotatedElementUtils.hasAnnotation(method, RequireWorkspaceAccess.class),
                method.getName() + " should not require workspace access");
    }
}
