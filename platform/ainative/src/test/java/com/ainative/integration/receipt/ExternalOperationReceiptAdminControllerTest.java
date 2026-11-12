package com.ainative.integration.receipt;

import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.common.error.BizException;
import com.ainative.context.RequestContext;
import com.ainative.integration.receipt.dto.ManualReceiptConfirmSucceededRequest;
import com.ainative.integration.receipt.dto.ManualReceiptRetryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExternalOperationReceiptAdminControllerTest {

    private final ExternalOperationReceiptAdminService adminService =
            mock(ExternalOperationReceiptAdminService.class);
    private final ExternalOperationReceiptAdminController controller =
            new ExternalOperationReceiptAdminController(adminService);

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void retryPassesCurrentTenantAndOperatorToService() {
        context(7L, 9L);
        ManualReceiptRetryRequest request = new ManualReceiptRetryRequest();
        request.setReason("fixed permission");

        controller.retry(11L, request);

        verify(adminService).manualRetry(11L, 7L, 9L, "fixed permission");
    }

    @Test
    void confirmSucceededPassesReasonToService() {
        context(7L, 9L);
        ManualReceiptConfirmSucceededRequest request =
                new ManualReceiptConfirmSucceededRequest();
        request.setReason("verified in Zentao");

        controller.confirmSucceeded(11L, request);

        verify(adminService).manualConfirmSucceeded(11L, 7L, 9L, "verified in Zentao");
    }

    @Test
    void controllerRequiresOrganizationAdministratorAccess() {
        RequireWorkspaceAccess requirement = ExternalOperationReceiptAdminController.class
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(requirement);
        assertEquals(WorkspaceAccessLevel.ADMIN, requirement.value());
    }

    @Test
    void missingContextIsRejectedBeforeServiceCall() {
        ManualReceiptRetryRequest request = new ManualReceiptRetryRequest();
        request.setReason("retry");

        BizException error = assertThrows(BizException.class,
                () -> controller.retry(11L, request));

        assertEquals("11001", error.getCode());
    }

    private void context(long tenantId, long userId) {
        RequestContext.get().setCurrentWorkspaceId(tenantId);
        RequestContext.get().setUserId(userId);
    }
}
