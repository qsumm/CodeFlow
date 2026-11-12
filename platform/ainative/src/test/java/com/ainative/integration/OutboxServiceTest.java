package com.ainative.integration;

import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import com.ainative.integration.common.ExternalCommentLinkDao;
import com.ainative.integration.common.ExternalWorkitemLinkDO;
import com.ainative.integration.common.ExternalWorkitemLinkDao;
import com.ainative.integration.common.IntegrationOutboxDao;
import com.ainative.integration.event.WorkitemCommentCreatedEvent;
import com.ainative.integration.event.WorkitemContentUpdatedEvent;
import com.ainative.integration.event.WorkitemStatusChangedEvent;
import com.ainative.integration.receipt.ExternalOperationReceiptService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceTest {


    @Test
    void agentCommentCreatedEventEnqueuesExternalCommentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalActorIdentityResolver identityResolver = mock(ExternalActorIdentityResolver.class);
        OutboxService service = service(linkDao, commentLinkDao, outboxDao, identityResolver);
        ExternalWorkitemLinkDO link = activeLink();

        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(link));
        when(commentLinkDao.findByLocalComment(10000L, 88001L)).thenReturn(null);
        when(identityResolver.resolve("AGENT", 10001L))
                .thenReturn(new ExternalActorIdentityResolver.Identity("代码助手", "Agent: 代码助手（ID: 10001）"));

        service.onCommentCreated(new WorkitemCommentCreatedEvent(10000L, 500L, 88001L,
                "AGENT", 10001L, "关键结论：已完成"));

        verify(outboxDao).insert(argThat(outbox -> {
            JSONObject payload = JSON.parseObject(outbox.getPayloadJson());
            assertEquals(10000L, outbox.getTenantId());
            assertEquals("ZENTAO", outbox.getProvider());
            assertEquals(1L, outbox.getBindingId());
            assertEquals(500L, outbox.getWorkitemId());
            assertEquals("COMMENT_CREATE", outbox.getEventType());
            assertEquals("PENDING", outbox.getStatus());
            assertEquals(0, outbox.getRetryCount());
            assertEquals("84189105", payload.getString("externalWorkitemId"));
            assertEquals(88001L, payload.getLongValue("commentId"));
            assertFalse(payload.containsKey("contentMd"));
            assertEquals(64, payload.getString("contentDigest").length());
            assertTrue(payload.getString("marker").startsWith("<!-- an-op:"));
            assertTrue(outbox.getOperationKey().startsWith("zentao.comment:"));
            assertEquals(0L, outbox.getLockVersion());
            return true;
        }));
    }

    @Test
    void deletedExternalWorkitemDoesNotAcceptCommentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        OutboxService service = service(linkDao, commentLinkDao, outboxDao,
                mock(ExternalActorIdentityResolver.class));
        ExternalWorkitemLinkDO link = activeLink();
        link.setSourceLifecycle("DELETED");

        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(link));

        service.onCommentCreated(new WorkitemCommentCreatedEvent(
                10000L, 500L, 88001L, "HUMAN", 10001L, "不应写回"));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
        verify(commentLinkDao, never()).findByLocalComment(10000L, 88001L);
    }

    @Test
    void contentUpdatedEventDoesNotWriteBackSourceOwnedContent() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        OutboxService service = service(linkDao, mock(ExternalCommentLinkDao.class), outboxDao,
                mock(ExternalActorIdentityResolver.class));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "新标题", "新正文", 7L));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
        verify(linkDao, never()).listByWorkitem(10000L, 500L);
    }

    @Test
    void nonDoneStatusChangeDoesNotOverwriteExternalBusinessStatus() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        com.ainative.statemachine.StatusNodeDao nodeDao = mock(com.ainative.statemachine.StatusNodeDao.class);
        com.ainative.statemachine.StatusNodeDO inProgress = new com.ainative.statemachine.StatusNodeDO();
        inProgress.setId(1001L);
        inProgress.setCategory("IN_PROGRESS");
        org.mockito.Mockito.when(nodeDao.findById(1001L)).thenReturn(inProgress);
        OutboxService service = service(linkDao, mock(ExternalCommentLinkDao.class), outboxDao,
                mock(ExternalActorIdentityResolver.class), nodeDao);

        service.onStatusChanged(new WorkitemStatusChangedEvent(10000L, 500L, 1001L, 7L));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
    }

    @Test
    void doneStatusChangeEnqueuesExternalStatusUpdate() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        com.ainative.statemachine.StatusNodeDao nodeDao = mock(com.ainative.statemachine.StatusNodeDao.class);
        com.ainative.statemachine.StatusNodeDO done = new com.ainative.statemachine.StatusNodeDO();
        done.setId(1002L);
        done.setCategory("DONE");
        done.setName("已关闭");
        org.mockito.Mockito.when(nodeDao.findById(1002L)).thenReturn(done);
        org.mockito.Mockito.when(linkDao.listByWorkitem(10000L, 500L))
                .thenReturn(java.util.List.of(activeLink()));
        OutboxService service = service(linkDao, mock(ExternalCommentLinkDao.class), outboxDao,
                mock(ExternalActorIdentityResolver.class), nodeDao);

        service.onStatusChanged(new WorkitemStatusChangedEvent(10000L, 500L, 1002L, 7L));

        verify(outboxDao).insert(argThat(outbox ->
                "STATUS_UPDATE".equals(outbox.getEventType())
                        && outbox.getPayloadJson().contains("84189105")
                        && outbox.getPayloadJson().contains("已关闭")));
    }

    private OutboxService service(ExternalWorkitemLinkDao linkDao,
                                      ExternalCommentLinkDao commentLinkDao,
                                      IntegrationOutboxDao outboxDao,
                                      ExternalActorIdentityResolver identityResolver) {
        return service(linkDao, commentLinkDao, outboxDao, identityResolver,
                org.mockito.Mockito.mock(com.ainative.statemachine.StatusNodeDao.class));
    }

    private OutboxService service(ExternalWorkitemLinkDao linkDao,
                                      ExternalCommentLinkDao commentLinkDao,
                                      IntegrationOutboxDao outboxDao,
                                      ExternalActorIdentityResolver identityResolver,
                                      com.ainative.statemachine.StatusNodeDao nodeDao) {
        return new OutboxService(linkDao, commentLinkDao, new ExternalCommentFormatter(),
                identityResolver, new ExternalOperationReceiptService(outboxDao), nodeDao);
    }

    private ExternalWorkitemLinkDO activeLink() {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setTenantId(10000L);
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        link.setProvider("ZENTAO");
        link.setSourceLifecycle("ACTIVE");
        return link;
    }
}
