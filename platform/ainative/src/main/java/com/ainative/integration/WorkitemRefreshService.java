package com.ainative.integration;

import com.ainative.configuration.ThreadPoolManager;
import com.ainative.integration.dto.SyncNowResult;
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.common.ExternalWorkitemLinkDO;
import com.ainative.integration.common.ExternalWorkitemLinkDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class WorkitemRefreshService {

    private static final Logger log = LoggerFactory.getLogger(WorkitemRefreshService.class);

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalProjectBindingDao bindingDao;
    private final InboundSyncService inboundSyncService;
    private final Executor refreshExecutor;

    // Workitems with a refresh already queued or running. Coalesces repeated reads of the same
    // workitem into a single refresh so rapid re-reads don't pile duplicate tasks onto the shared
    // external platform rate budget (the same quota exhaustion that caused the original 20s read stalls).
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public WorkitemRefreshService(ExternalWorkitemLinkDao linkDao, ExternalProjectBindingDao bindingDao,
                                      InboundSyncService inboundSyncService) {
        this(linkDao, bindingDao, inboundSyncService, ThreadPoolManager.networkCallPool);
    }

    WorkitemRefreshService(ExternalWorkitemLinkDao linkDao, ExternalProjectBindingDao bindingDao,
                               InboundSyncService inboundSyncService, Executor refreshExecutor) {
        this.linkDao = linkDao;
        this.bindingDao = bindingDao;
        this.inboundSyncService = inboundSyncService;
        this.refreshExecutor = refreshExecutor;
    }

    /**
     * Triggers a best-effort refresh of an externally linked workitem off the request thread. The refresh
     * makes throttled remote platform calls that can block for up to 20s when the shared rate bucket is
     * drained (e.g. while a large project is being polled), so it must never run on the read path —
     * the detail endpoint serves the local snapshot immediately and the executor catches up.
     */
    public void refreshIfLinked(long workitemId, long tenantId, long userId) {
        if (!inFlight.add(workitemId)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> refreshNow(workitemId, tenantId, userId));
        } catch (RejectedExecutionException e) {
            inFlight.remove(workitemId);
            log.warn("external detail refresh rejected, skipping workitemId={} error={}", workitemId, e.getMessage());
        }
    }

    /**
     * 立即对帐(同步):拉取该工作项的外部详情并回写本地,返回同步计数;未连接外部链返回 null。
     * 与 {@link #refreshIfLinked} 不同:这是用户显式点击「立即对帐」的同步路径,愿意等远端调用,
     * 且失败要向上抛出而不是吞掉。
     */
    public SyncNowResult syncNow(long workitemId, long tenantId, long userId) {
        ExternalWorkitemLinkDO link = resolveLinkedWorkitem(tenantId, workitemId);
        if (link == null) {
            return null;
        }
        ExternalProjectBindingDO binding = bindingDao.findById(link.getBindingId());
        if (binding == null || !Long.valueOf(tenantId).equals(binding.getTenantId())) {
            return null;
        }
        return inboundSyncService.refreshIssueIds(binding, List.of(link.getExternalWorkitemId()), userId);
    }

    void refreshNow(long workitemId, long tenantId, long userId) {
        try {
            ExternalWorkitemLinkDO link = resolveLinkedWorkitem(tenantId, workitemId);
            if (link == null) {
                return;
            }
            ExternalProjectBindingDO binding = bindingDao.findById(link.getBindingId());
            if (binding == null || !Long.valueOf(tenantId).equals(binding.getTenantId())) {
                log.warn("external detail refresh skipped, binding missing workitemId={} bindingId={}", workitemId, link.getBindingId());
                return;
            }
            try {
                inboundSyncService.refreshIssueIds(binding, List.of(link.getExternalWorkitemId()), userId);
            } catch (RuntimeException e) {
                log.warn("external detail refresh failed workitemId={} externalWorkitemId={} error={}",
                        workitemId, link.getExternalWorkitemId(), e.getMessage());
                log.debug("external detail refresh exception", e);
            }
        } finally {
            inFlight.remove(workitemId);
        }
    }

    /**
     * 多源:工单可能挂在任一 provider 的外部链上;按工单第一条外部链刷新,
     * 其它 provider(禅道等)的详情刷新照常触发。
     */
    private ExternalWorkitemLinkDO resolveLinkedWorkitem(long tenantId, long workitemId) {
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

}
