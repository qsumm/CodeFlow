package com.ainative.scheduledtask.compat;

import com.ainative.access.WorkspaceAccessAspect;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.common.error.BizException;
import com.ainative.context.RequestContext;
import com.ainative.scheduledtask.ScheduledTaskProperties;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskCapabilityAspectSpringTest {

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
    }

    @Test
    void orgAuthorizationHasExplicitPrecedenceOverCapabilityRejection() {
        Order orgOrder = AnnotationUtils.findAnnotation(WorkspaceAccessAspect.class, Order.class);
        Order capabilityOrder = AnnotationUtils.findAnnotation(
                ScheduledTaskCapabilityAspect.class, Order.class);

        assertTrue(orgOrder.value() < capabilityOrder.value());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                TestConfiguration.class)) {
            ProtectedService service = context.getBean(ProtectedService.class);
            MetricRegistry registry = context.getBean(MetricRegistry.class);

            BizException unauthorized = assertThrows(BizException.class, service::invoke);
            assertEquals("10401", unauthorized.getCode());
            assertFalse(registry.getMeters().containsKey(
                    "scheduled_task_schema_not_ready_total:,entry=http"));

            RequestContext.get().setUserId(7L);
            RequestContext.get().setCurrentWorkspaceId(9L);
            RequestContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
            BizException unavailable = assertThrows(BizException.class, service::invoke);
            assertEquals("30006", unavailable.getCode());
            assertEquals(1, registry.getMeters().get(
                    "scheduled_task_schema_not_ready_total:,entry=http").getCount());
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        MetricRegistry metricRegistry() {
            return new MetricRegistry();
        }

        @Bean
        V037SchemaCapability capability() {
            return new V037SchemaCapability(V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                    false, false, false, Set.of(), Instant.EPOCH);
        }

        @Bean
        ScheduledTaskProperties properties() {
            ScheduledTaskProperties properties = new ScheduledTaskProperties();
            properties.setEnabled(true);
            properties.setClusterReadyAttestation(true);
            return properties;
        }

        @Bean
        V037CompatibilityMetrics metrics(MetricRegistry registry, V037SchemaCapability capability) {
            return new V037CompatibilityMetrics(registry, capability);
        }

        @Bean
        ScheduledTaskCapabilityGuard guard(V037SchemaCapability capability,
                                           ScheduledTaskProperties properties,
                                           V037CompatibilityMetrics metrics) {
            return new ScheduledTaskCapabilityGuard(capability, properties, metrics);
        }

        @Bean
        WorkspaceAccessAspect workspaceAccessAspect() {
            return new WorkspaceAccessAspect();
        }

        @Bean
        ScheduledTaskCapabilityAspect capabilityAspect(ScheduledTaskCapabilityGuard guard) {
            return new ScheduledTaskCapabilityAspect(guard);
        }

        @Bean
        ProtectedService protectedService() {
            return new ProtectedService();
        }
    }

    @RequireWorkspaceAccess(WorkspaceAccessLevel.ADMIN)
    @RequiresScheduledTaskCapability(entry = "http")
    static class ProtectedService {
        public void invoke() {
        }
    }
}
