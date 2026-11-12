package com.ainative.integration;

import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.common.ExternalWorkitemLinkDO;
import com.ainative.integration.common.ExternalWorkitemLinkDao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemRefreshServiceTest {

    // Runs submitted tasks inline so the refresh body under test executes deterministically.
    private static final Executor INLINE = Runnable::run;

    @Test
    void springCanResolveAConstructorForAutowiring() {
        // The service has two constructors and no no-arg constructor. Spring only auto-detects a
        // constructor to inject when exactly one exists or one is annotated @Autowired; otherwise it
        // falls back to the (missing) default constructor and startup fails with
        // NoSuchMethodException: <init>(). Guards against that BeanInstantiationException regression.
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                WorkitemRefreshService.class, "externalWorkitemRefreshService");
        assertNotNull(candidates, "Spring found no constructor to autowire; bean cannot be instantiated");
    }

    @Test
    void refreshIfLinkedDelegatesToDetailSync() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE);
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of(link));
        when(bindingDao.findById(1L)).thenReturn(binding);

        service.refreshIfLinked(500L, 100L, 9L);

        verify(inboundSyncService).refreshIssueIds(binding, List.of("84189105"), 9L);
    }

    @Test
    void refreshIfLinkedRunsOffTheCallerThreadViaExecutor() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add);
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of(link));
        when(bindingDao.findById(1L)).thenReturn(binding);

        service.refreshIfLinked(500L, 100L, 9L);

        // The request path must not touch the DB or Zentao synchronously; work is deferred to the executor.
        verify(linkDao, never()).listByWorkitem(anyLong(), anyLong());
        verify(inboundSyncService, never()).refreshIssueIds(any(), any(), anyLong());
        assertEquals(1, submitted.size());

        submitted.get(0).run();

        verify(inboundSyncService).refreshIssueIds(binding, List.of("84189105"), 9L);
    }

    @Test
    void refreshIfLinkedSkipsUnlinkedWorkitem() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE);

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of());

        service.refreshIfLinked(500L, 100L, 9L);

        verify(bindingDao, never()).findById(1L);
        verify(inboundSyncService, never()).refreshIssueIds(any(), any(), anyLong());
    }

    @Test
    void refreshIfLinkedDoesNotThrowWhenZentaoRefreshFails() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE);
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of(link));
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(inboundSyncService.refreshIssueIds(binding, List.of("84189105"), 9L))
                .thenThrow(new RuntimeException("Zentao request failed"));

        service.refreshIfLinked(500L, 100L, 9L);
    }

    @Test
    void refreshIfLinkedDeduplicatesInFlightRefreshesForSameWorkitem() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add);

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of(link()));
        when(bindingDao.findById(1L)).thenReturn(binding());

        // Two reads of the same workitem arrive before the first refresh runs.
        service.refreshIfLinked(500L, 100L, 9L);
        service.refreshIfLinked(500L, 100L, 9L);

        // Only one refresh is enqueued; the shared Zentao quota is not charged twice.
        assertEquals(1, submitted.size());

        // Once the in-flight refresh finishes, the workitem is eligible to refresh again.
        submitted.get(0).run();
        service.refreshIfLinked(500L, 100L, 9L);
        assertEquals(2, submitted.size());
    }

    @Test
    void refreshIfLinkedDoesNotDeduplicateDifferentWorkitems() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add);

        service.refreshIfLinked(500L, 100L, 9L);
        service.refreshIfLinked(501L, 100L, 9L);

        assertEquals(2, submitted.size());
    }

    @Test
    void refreshIfLinkedClearsInFlightMarkerWhenEnqueueRejected() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        InboundSyncService inboundSyncService = mock(InboundSyncService.class);
        AtomicInteger executeAttempts = new AtomicInteger();
        Executor rejecting = command -> {
            executeAttempts.incrementAndGet();
            throw new RejectedExecutionException("queue full");
        };
        WorkitemRefreshService service = new WorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                rejecting);

        service.refreshIfLinked(500L, 100L, 9L);
        // A rejected enqueue must not leave a stale in-flight marker, otherwise the workitem could
        // never be refreshed again.
        service.refreshIfLinked(500L, 100L, 9L);

        assertEquals(2, executeAttempts.get());
    }

    private ExternalWorkitemLinkDO link() {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(10L);
        link.setTenantId(100L);
        link.setProvider("ZENTAO");
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        return link;
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("ZENTAO");
        binding.setExternalProjectId("2161074");
        return binding;
    }
}
