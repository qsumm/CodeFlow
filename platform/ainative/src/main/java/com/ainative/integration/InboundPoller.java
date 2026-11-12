package com.ainative.integration;

import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.provider.ExternalWorkitemProvider;
import com.ainative.integration.provider.ExternalWorkitemSummary;
import com.ainative.integration.provider.PageResult;
import com.ainative.security.crypto.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
// 多源:按绑定逐 provider 轮询;无绑定时空转,无副作用
public class InboundPoller {

    private static final Logger log = LoggerFactory.getLogger(InboundPoller.class);

    /**
     * Floor on how often a single binding is polled, regardless of its configured
     * interval. Remote platforms often share a quota across all bindings and endpoints,
     * so full-project scans must be spaced out — otherwise never-succeeded bindings
     * would be re-scanned on every 3s scheduler tick and exhaust the quota.
     */
    static final int MIN_POLL_INTERVAL_SECONDS = 15;

    /**
     * How far before lastSuccessAt an incremental poll re-scans. Covers items created while the
     * previous full scan was running so they aren't skipped by the createdAt lower bound.
     */
    private static final long INCREMENTAL_OVERLAP_MILLIS = 60 * 60 * 1000L;
    private static final int RECONCILE_BATCH_SIZE = 100;

    private final ExternalProjectBindingDao bindingDao;
    private final SecretCrypto secretCrypto;
    /** 按绑定 provider 分发到对应 Provider 实现。 */
    private final List<ExternalWorkitemProvider> workitemProviders;
    private final InboundSyncService inboundSyncService;
    private final Map<Long, Long> lastAttemptAt = new ConcurrentHashMap<>();

    public InboundPoller(ExternalProjectBindingDao bindingDao, SecretCrypto secretCrypto,
                             List<ExternalWorkitemProvider> workitemProviders,
                             InboundSyncService inboundSyncService) {
        this.bindingDao = bindingDao;
        this.secretCrypto = secretCrypto;
        this.workitemProviders = workitemProviders;
        this.inboundSyncService = inboundSyncService;
    }

    private ExternalWorkitemProvider providerFor(ExternalProjectBindingDO binding) {
        String provider = binding.getProvider();
        for (ExternalWorkitemProvider candidate : workitemProviders) {
            if (candidate.provider().equalsIgnoreCase(provider)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no workitem provider for binding provider=" + provider);
    }

    @Scheduled(fixedDelay = 3000)
    public void pollScheduled() {
        pollOnce();
    }

    public synchronized int pollOnce() {
        int synced = 0;
        // 每个 provider 各拉自己的启用绑定,实际承担全部外部工单源
        for (ExternalWorkitemProvider provider : workitemProviders) {
            synced += pollProvider(provider);
        }
        return synced;
    }

    private int pollProvider(ExternalWorkitemProvider workitemProvider) {
        int synced = 0;
        for (ExternalProjectBindingDO binding : bindingDao.listEnabled(workitemProvider.provider())) {
            if (!shouldPoll(binding)) {
                continue;
            }
            lastAttemptAt.put(binding.getId(), System.currentTimeMillis());
            try {
                log.info("inbound poll start bindingId={} tenantId={} projectId={} projectName={}",
                        binding.getId(), binding.getTenantId(), binding.getExternalProjectId(),
                        binding.getExternalProjectName());
                ExternalPlatformConfig config = new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(),
                        secretCrypto.decrypt(binding.getCredentialRef()),
                        binding.getRegionId(), binding.getExternalProjectId());
                PageResult<ExternalWorkitemSummary> page = workitemProvider.searchProject(config,
                        binding.getExternalProjectId(), incrementalFrom(binding), null);
                List<String> issueIds = page.getItems() == null ? List.of()
                        : page.getItems().stream()
                        .map(ExternalWorkitemSummary::getExternalId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
                if (!issueIds.isEmpty()) {
                    inboundSyncService.syncWorkitems(binding, page.getItems(), actorId(binding));
                    bindingDao.markSyncSuccess(binding.getId(), binding.getTenantId(), new Date());
                    synced += issueIds.size();
                    log.info("inbound poll success bindingId={} projectId={} syncedIssueCount={}",
                            binding.getId(), binding.getExternalProjectId(), issueIds.size());
                } else {
                    bindingDao.markSyncSuccess(binding.getId(), binding.getTenantId(), new Date());
                    log.info("inbound poll success (no workitems) bindingId={} projectId={}",
                            binding.getId(), binding.getExternalProjectId());
                }
                int reconciled = inboundSyncService.reconcileLinkedWorkitems(
                        binding, actorId(binding), RECONCILE_BATCH_SIZE);
                synced += reconciled;
                if (reconciled > 0) {
                    log.info("linked workitem reconciliation success bindingId={} reconciledCount={}",
                            binding.getId(), reconciled);
                }
            } catch (Exception e) {
                bindingDao.markSyncFailure(binding.getId(), binding.getTenantId(), e.getMessage());
                log.warn("inbound poll failed bindingId={} tenantId={} projectId={} projectName={} error={}",
                        binding.getId(), binding.getTenantId(), binding.getExternalProjectId(),
                        binding.getExternalProjectName(), e.getMessage(), e);
            }
        }
        return synced;
    }

    private boolean shouldPoll(ExternalProjectBindingDO binding) {
        long intervalMillis = intervalSeconds(binding) * 1000L;
        long now = System.currentTimeMillis();

        Long attemptedAt = lastAttemptAt.get(binding.getId());
        if (attemptedAt != null && now < attemptedAt + intervalMillis) {
            return false;
        }

        Date lastSuccessAt = binding.getLastSuccessAt();
        if (lastSuccessAt == null) {
            return true;
        }
        return now >= lastSuccessAt.getTime() + intervalMillis;
    }

    private int intervalSeconds(ExternalProjectBindingDO binding) {
        int configured = binding.getPollIntervalSeconds() == null ? MIN_POLL_INTERVAL_SECONDS
                : binding.getPollIntervalSeconds();
        return Math.max(configured, MIN_POLL_INTERVAL_SECONDS);
    }

    /**
     * First poll (never succeeded) scans the whole project; later polls only fetch workitems
     * created since the last success, minus an overlap so items created during the previous scan
     * aren't missed. Re-reading overlapping items is harmless because syncWorkitems upserts by
     * the binding-scoped external workitem identity.
     */
    private Date incrementalFrom(ExternalProjectBindingDO binding) {
        Date lastSuccessAt = binding.getLastSuccessAt();
        if (lastSuccessAt == null) {
            return null;
        }
        return new Date(lastSuccessAt.getTime() - INCREMENTAL_OVERLAP_MILLIS);
    }

    private long actorId(ExternalProjectBindingDO binding) {
        return binding.getModifierId() == null ? binding.getCreatorId() == null ? 0L : binding.getCreatorId()
                : binding.getModifierId();
    }
}
