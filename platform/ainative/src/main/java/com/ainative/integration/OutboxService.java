package com.ainative.integration;

import com.ainative.json.JSONObject;
import com.ainative.integration.common.ExternalCommentLinkDao;
import com.ainative.integration.common.ExternalWorkitemLinkDO;
import com.ainative.integration.common.ExternalWorkitemLinkDao;
import com.ainative.integration.event.WorkitemCommentCreatedEvent;
import com.ainative.integration.event.WorkitemContentUpdatedEvent;
import com.ainative.integration.event.WorkitemStatusChangedEvent;
import com.ainative.statemachine.StatusNodeDao;
import com.ainative.statemachine.StatusNodeDO;
import com.ainative.workitem.WorkitemClassificationEvaluator;
import com.ainative.integration.receipt.ExternalOperationDigests;
import com.ainative.integration.receipt.ExternalOperationKeys;
import com.ainative.integration.receipt.ExternalOperationReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final ExternalCommentFormatter commentFormatter;
    private final ExternalActorIdentityResolver identityResolver;
    private final ExternalOperationReceiptService receiptService;
    private final StatusNodeDao nodeDao;

    public OutboxService(ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                             ExternalCommentFormatter commentFormatter,
                             ExternalActorIdentityResolver identityResolver,
                             ExternalOperationReceiptService receiptService,
                             StatusNodeDao nodeDao) {
        this.linkDao = linkDao;
        this.commentLinkDao = commentLinkDao;
        this.commentFormatter = commentFormatter;
        this.identityResolver = identityResolver;
        this.receiptService = receiptService;
        this.nodeDao = nodeDao;
    }

    @EventListener
    public void onCommentCreated(WorkitemCommentCreatedEvent event) {
        ExternalWorkitemLinkDO link = resolveOutboundLink(event.tenantId(), event.workitemId());
        if (link == null) {
            log.debug("skip outbound comment sync, no external link tenantId={} workitemId={} commentId={}",
                    event.tenantId(), event.workitemId(), event.commentId());
            return;
        }
        if (blocksOutbound(link)) {
            log.info("skip outbound comment sync, link blocked tenantId={} workitemId={} commentId={} sourceLifecycle={} lastErrorCode={}",
                    event.tenantId(), event.workitemId(), event.commentId(), link.getSourceLifecycle(), link.getLastErrorCode());
            return;
        }
        if (commentLinkDao.findByLocalComment(event.tenantId(), event.commentId()) != null) {
            log.debug("skip outbound comment sync, comment already linked tenantId={} workitemId={} commentId={}",
                    event.tenantId(), event.workitemId(), event.commentId());
            return;
        }
        ExternalActorIdentityResolver.Identity identity = identityResolver.resolve(event.actorType(), event.actorRef());
        String content = commentFormatter.format(identity.displayName(), identity.sourceText(), event.contentMd());
        String operationKey = ExternalOperationKeys.comment(link.getProvider(), event.workitemId(), event.commentId());
        String marker = ExternalOperationKeys.marker(operationKey);
        JSONObject payload = new JSONObject(true);
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("commentId", event.commentId());
        payload.put("contentDigest", ExternalOperationDigests.textDigest(content));
        payload.put("marker", marker);
        ExternalOperationReceiptService.ReceiptResult result = enqueue(
                event.tenantId(), link.getProvider(), link.getBindingId(), event.workitemId(),
                "COMMENT_CREATE", operationKey, payload);
        log.info("recorded outbound comment receipt receiptId={} created={} tenantId={} workitemId={} commentId={} externalWorkitemId={}",
                result.receipt().getId(), result.created(), event.tenantId(), event.workitemId(), event.commentId(),
                link.getExternalWorkitemId());
    }

    @EventListener
    public void onContentUpdated(WorkitemContentUpdatedEvent event) {
        // External workitem content is source-owned. Kept as an explicit no-op so any
        // legacy or manually published local edit event cannot create a writeback loop.
    }

    /**
     * 状态回写(DEF-032):本地工单流转到 DONE 类节点时,把外部工单置为对应的外部状态。
     * 仅流向完成类;外部状态名传节点名(由 provider 的合法转换表归一,非法名落 default 分支)。
     * CANCELED 不回写——取消是本地语义,不应替外部对象定状态。
     */
    @EventListener
    public void onStatusChanged(WorkitemStatusChangedEvent event) {
        ExternalWorkitemLinkDO link = resolveOutboundLink(event.tenantId(), event.workitemId());
        if (link == null || blocksOutbound(link)) {
            return;
        }
        StatusNodeDO toNode = nodeDao.findById(event.toNodeId());
        if (toNode == null || !WorkitemClassificationEvaluator.CATEGORY_DONE.equals(toNode.getCategory())) {
            return;
        }
        JSONObject payload = new JSONObject(true);
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("externalStatusName", toNode.getName());
        String operationKey = ExternalOperationKeys.status(link.getProvider(), event.workitemId(), toNode.getId());
        ExternalOperationReceiptService.ReceiptResult result = enqueue(
                event.tenantId(), link.getProvider(), link.getBindingId(), event.workitemId(),
                "STATUS_UPDATE", operationKey, payload);
        log.info("recorded outbound status receipt receiptId={} created={} tenantId={} workitemId={} node={} externalWorkitemId={}",
                result.receipt().getId(), result.created(), event.tenantId(), event.workitemId(),
                toNode.getId(), link.getExternalWorkitemId());
    }

    private ExternalOperationReceiptService.ReceiptResult enqueue(
            long tenantId, String provider, long bindingId, long workitemId,
            String eventType, String operationKey, JSONObject payload) {
        return receiptService.begin(new ExternalOperationReceiptService.BeginRequest(
                tenantId, provider, bindingId, workitemId, eventType, operationKey,
                payload.toJSONString()));
    }

    /**
     * 按工单的第一条外部链回写备注(工单可能关联多平台时取首条)。
     */
    private ExternalWorkitemLinkDO resolveOutboundLink(long tenantId, long workitemId) {
        List<ExternalWorkitemLinkDO> links = linkDao.listByWorkitem(tenantId, workitemId);
        if (links == null || links.isEmpty()) {
            return null;
        }
        for (ExternalWorkitemLinkDO link : links) {
            if (link != null) {
                return link;
            }
        }
        return null;
    }

    private boolean blocksOutbound(ExternalWorkitemLinkDO link) {
        return "DELETED".equals(link.getSourceLifecycle())
                || "UNAVAILABLE".equals(link.getSourceLifecycle())
                || "ITEM_FORBIDDEN".equals(link.getLastErrorCode());
    }

}
