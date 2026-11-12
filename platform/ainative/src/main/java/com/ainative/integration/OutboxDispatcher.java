package com.ainative.integration;

import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import com.ainative.integration.common.ExternalCommentLinkDO;
import com.ainative.integration.common.ExternalCommentLinkDao;
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.common.IntegrationOutboxDO;
import com.ainative.integration.common.IntegrationOutboxDao;
import com.ainative.integration.generic.GenericHttpWorkitemWritebackProvider;
import com.ainative.integration.provider.ExternalComment;
import com.ainative.integration.provider.ExternalWorkitemProvider;
import com.ainative.integration.receipt.ExternalOperationDigests;
import com.ainative.integration.receipt.ExternalOperationSanitizer;
import com.ainative.security.crypto.SecretCrypto;
import com.ainative.workitem.WorkitemCommentDO;
import com.ainative.workitem.WorkitemCommentDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final String MISSING_WRITEBACK_STAFF_ID = "writeback staffId is required";

    /**
     * A transient row that still fails after this many attempts remains FAILED without another
     * retry timestamp. This bounds worst-case quota consumption without adding another state.
     */
    static final int MAX_RETRIES = 10;

    private final IntegrationOutboxDao outboxDao;
    private final ExternalProjectBindingDao bindingDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final Map<String, ExternalWorkitemProvider> workitemProviders;
    private final SecretCrypto secretCrypto;
    private final GenericHttpWorkitemWritebackProvider genericWritebackProvider;
    private final WorkitemCommentDao workitemCommentDao;
    private final ExternalCommentFormatter commentFormatter;
    private final ExternalActorIdentityResolver identityResolver;

    @Autowired
    public OutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                                ExternalCommentLinkDao commentLinkDao, List<ExternalWorkitemProvider> workitemProviders,
                                SecretCrypto secretCrypto,
                                GenericHttpWorkitemWritebackProvider genericWritebackProvider,
                                WorkitemCommentDao workitemCommentDao,
                                ExternalCommentFormatter commentFormatter,
                                ExternalActorIdentityResolver identityResolver) {
        this.outboxDao = outboxDao;
        this.bindingDao = bindingDao;
        this.commentLinkDao = commentLinkDao;
        this.workitemProviders = toProviderMap(workitemProviders);
        this.secretCrypto = secretCrypto;
        this.genericWritebackProvider = genericWritebackProvider;
        this.workitemCommentDao = workitemCommentDao;
        this.commentFormatter = commentFormatter;
        this.identityResolver = identityResolver;
    }

    OutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                         ExternalCommentLinkDao commentLinkDao,
                         List<ExternalWorkitemProvider> workitemProviders,
                         SecretCrypto secretCrypto,
                         GenericHttpWorkitemWritebackProvider genericWritebackProvider) {
        this(outboxDao, bindingDao, commentLinkDao, workitemProviders, secretCrypto,
                genericWritebackProvider, null, null, null);
    }

    @Scheduled(fixedDelay = 3000)
    public void dispatchScheduled() {
        dispatchPending(20);
    }

    public int dispatchPending(int limit) {
        List<IntegrationOutboxDO> items = outboxDao.listPendingAny(limit);
        int success = 0;
        for (IntegrationOutboxDO item : items) {
            if (dispatchOne(item)) {
                success++;
            }
        }
        return success;
    }


    private boolean dispatchOne(IntegrationOutboxDO item) {
        long expectedLockVersion = lockVersion(item);
        if (outboxDao.markSending(item.getId(), expectedLockVersion) != 1) {
            return false;
        }
        item.setLockVersion(expectedLockVersion + 1);
        ExternalProjectBindingDO binding = bindingDao.findById(item.getBindingId());
        if (binding == null) {
            fail(item, false, "binding not found");
            return false;
        }
        if (!item.getTenantId().equals(binding.getTenantId())) {
            fail(item, false, "binding tenant mismatch");
            return false;
        }
        if (!sameProvider(item.getProvider(), binding.getProvider())) {
            fail(item, false, "binding provider mismatch");
            return false;
        }
        ExternalWorkitemProvider workitemProvider = workitemProviders.get(providerKey(item.getProvider()));
        boolean useGenericContentWriteback = workitemProvider == null && canUseGenericContentWriteback(item);
        if (workitemProvider == null && !useGenericContentWriteback) {
            fail(item, false, "provider not supported: " + item.getProvider());
            return false;
        }
        if ("STATUS_UPDATE_SKIPPED".equals(item.getEventType())) {
            fail(item, false, "status update skipped: missing mapping");
            return false;
        }
        if (requiresWritebackStaff(item) && isBlank(binding.getWritebackStaffId())) {
            fail(item, true, MISSING_WRITEBACK_STAFF_ID);
            return false;
        }
        ExternalPlatformConfig config = new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(),
                decryptCredential(binding.getCredentialRef()), binding.getRegionId(), binding.getExternalProjectId());
        JSONObject payload = JSON.parseObject(item.getPayloadJson());
        boolean externalEffectReturned = false;
        try {
            if ("COMMENT_CREATE".equals(item.getEventType())) {
                String content = commentContent(item, payload);
                ExternalComment comment = workitemProvider.createComment(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), content);
                externalEffectReturned = true;
                if (comment.getExternalId() != null) {
                    ExternalCommentLinkDO link = new ExternalCommentLinkDO();
                    link.setTenantId(item.getTenantId());
                    link.setProvider(item.getProvider());
                    link.setBindingId(item.getBindingId());
                    link.setExternalWorkitemId(payload.getString("externalWorkitemId"));
                    link.setExternalCommentId(comment.getExternalId());
                    link.setWorkitemCommentId(payload.getLong("commentId"));
                    link.setDirection("OUTBOUND");
                    link.setSourceUpdatedAt(comment.getUpdatedAt());
                    link.setSourceStatus(isBlank(comment.getSourceStatus()) ? "ACTIVE" : comment.getSourceStatus());
                    commentLinkDao.insert(link);
                }
            } else if ("STATUS_UPDATE".equals(item.getEventType())) {
                workitemProvider.updateStatus(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), payload.getString("externalStatusName"));
                externalEffectReturned = true;
            } else if ("CONTENT_UPDATE".equals(item.getEventType())) {
                if (useGenericContentWriteback) {
                    genericWritebackProvider.updateContent(item.getProvider(), config,
                            payload.getString("externalWorkitemId"), payload.getString("title"),
                            payload.getString("contentMd"));
                } else {
                    workitemProvider.updateContent(config, payload.getString("externalWorkitemId"),
                            binding.getWritebackStaffId(), payload.getString("title"),
                            payload.getString("contentMd"));
                }
                externalEffectReturned = true;
            }
            return outboxDao.markSucceeded(item.getId(), lockVersion(item)) == 1;
        } catch (Exception e) {
            if (externalEffectReturned || isAmbiguous(e)) {
                markUnknown(item, e);
            } else {
                fail(item, failureRetryable(item, e), e);
            }
            return false;
        }
    }

    /**
     * Anything that can never succeed on retry remains FAILED so it stops consuming the
     * shared external quota: provider-classified terminal errors (business rejections, 4xx) and
     * rows that have already exhausted {@link #MAX_RETRIES} attempts.
     */
    private boolean failureRetryable(IntegrationOutboxDO item, Exception e) {
        return !isTerminal(e) && !exhaustedRetries(item);
    }

    private boolean exhaustedRetries(IntegrationOutboxDO item) {
        int current = item.getRetryCount() == null ? 0 : item.getRetryCount();
        return current + 1 >= MAX_RETRIES;
    }

    private boolean isTerminal(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ExternalPlatformException platform && platform.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    private boolean isAmbiguous(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause instanceof ExternalPlatformException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("non-JSON response")) {
                return true;
            }
        }
        return false;
    }

    private String commentContent(IntegrationOutboxDO item, JSONObject payload) {
        String legacyContent = payload.getString("contentMd");
        if (!isBlank(legacyContent)) {
            return withMarker(legacyContent, payload.getString("marker"));
        }
        if (workitemCommentDao == null || commentFormatter == null || identityResolver == null) {
            throw new IllegalStateException("comment payload source is unavailable");
        }
        WorkitemCommentDO comment = workitemCommentDao.findById(item.getTenantId(), payload.getLong("commentId"));
        if (comment == null || !item.getWorkitemId().equals(comment.getWorkitemId())) {
            throw new IllegalStateException("comment payload source changed or was removed");
        }
        ExternalActorIdentityResolver.Identity identity = identityResolver.resolve(
                comment.getAuthorType(), comment.getAuthorRef());
        String content = commentFormatter.format(identity.displayName(), identity.sourceText(), comment.getContentMd());
        if (!ExternalOperationDigests.textDigest(content).equals(payload.getString("contentDigest"))) {
            throw new IllegalStateException("comment payload source digest changed");
        }
        return withMarker(content, payload.getString("marker"));
    }

    private String withMarker(String content, String marker) {
        if (isBlank(marker) || content.contains(marker)) {
            return content;
        }
        return content + "\n\n" + marker;
    }

    private void fail(IntegrationOutboxDO item, boolean retryable, Throwable failure) {
        fail(item, retryable, errorMessage(failure));
    }

    private void fail(IntegrationOutboxDO item, boolean retryable, String error) {
        String safeError = ExternalOperationSanitizer.sanitizeError(error);
        int updated = outboxDao.markFailed(item.getId(), lockVersion(item), retryable, safeError);
        if (updated == 1) {
            log.warn("outbox dispatch failed id={} provider={} bindingId={} workitemId={} eventType={} retryable={} retryCount={} error={}",
                    item.getId(), item.getProvider(), item.getBindingId(), item.getWorkitemId(),
                    item.getEventType(), retryable, item.getRetryCount(), safeError);
        } else {
            log.debug("skip stale outbox failure result id={} lockVersion={}", item.getId(), lockVersion(item));
        }
    }

    private void markUnknown(IntegrationOutboxDO item, Throwable failure) {
        String safeError = safeError(failure);
        int updated = outboxDao.markUnknown(item.getId(), lockVersion(item), safeError);
        if (updated == 1) {
            log.warn("outbox dispatch result unknown id={} provider={} bindingId={} workitemId={} eventType={} retryCount={} error={}",
                    item.getId(), item.getProvider(), item.getBindingId(), item.getWorkitemId(),
                    item.getEventType(), item.getRetryCount(), safeError);
        } else {
            log.debug("skip stale outbox unknown result id={} lockVersion={}", item.getId(), lockVersion(item));
        }
    }

    private long lockVersion(IntegrationOutboxDO item) {
        return item.getLockVersion() == null ? 0L : item.getLockVersion();
    }

    /** 当前外部工单平台(ZenTao)不需要 writebackStaff;保留调用点以兼容后续平台差异。 */
    private boolean requiresWritebackStaff(IntegrationOutboxDO item) {
        return false;
    }

    private boolean canUseGenericContentWriteback(IntegrationOutboxDO item) {
        return genericWritebackProvider != null
                && "CONTENT_UPDATE".equals(item.getEventType());
    }

    private String decryptCredential(String credentialRef) {
        return isBlank(credentialRef) ? null : secretCrypto.decrypt(credentialRef);
    }

    private Map<String, ExternalWorkitemProvider> toProviderMap(List<ExternalWorkitemProvider> providers) {
        Map<String, ExternalWorkitemProvider> result = new HashMap<>();
        if (providers == null) {
            return result;
        }
        for (ExternalWorkitemProvider provider : providers) {
            if (provider == null || isBlank(provider.provider())) {
                continue;
            }
            result.put(providerKey(provider.provider()), provider);
        }
        return result;
    }

    private boolean sameProvider(String left, String right) {
        return providerKey(left).equals(providerKey(right));
    }

    private String providerKey(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String errorMessage(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String safeError(Throwable error) {
        return ExternalOperationSanitizer.sanitizeError(errorMessage(error));
    }
}
