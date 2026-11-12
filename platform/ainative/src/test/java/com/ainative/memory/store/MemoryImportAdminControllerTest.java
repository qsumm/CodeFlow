package com.ainative.memory.store;

import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class MemoryImportAdminControllerTest {
    @AfterEach
    void cleanup() {
        RequestContext.destroy();
    }

    @Test
    void delegatesTenantScopedImportAdministration() {
        MemoryImportAdminService service = mock(MemoryImportAdminService.class);
        MemoryImportAdminController controller = new MemoryImportAdminController(service);
        RequestContext.get().setCurrentWorkspaceId(7L);
        List<MemoryImportSourceDO> sources = List.of(new MemoryImportSourceDO());
        List<MemoryImportReceiptDO> receipts = List.of(new MemoryImportReceiptDO());
        when(service.listSources(7L)).thenReturn(sources);
        when(service.listReceipts(7L, 11L)).thenReturn(receipts);

        assertSame(sources, controller.listSources().getData());
        assertSame(receipts, controller.listReceipts(11L).getData());
        controller.changeStatus(11L, new MemoryImportAdminController.StatusRequest("PAUSED", 3));

        verify(service).changeStatus(7L, 11L, "PAUSED", 3);
    }
}
