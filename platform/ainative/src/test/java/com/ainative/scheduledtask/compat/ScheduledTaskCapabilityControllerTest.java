package com.ainative.scheduledtask.compat;

import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.common.result.Result;
import com.ainative.scheduledtask.ScheduledTaskProperties;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskCapabilityControllerTest {

    @Test
    void exposesSchemaIndependentReadOnlyCapabilityRoute() throws Exception {
        ScheduledTaskCapabilityController controller = new ScheduledTaskCapabilityController(
                guard(V037SchemaMode.V037_PARTIAL, false, true, true));

        Result<ScheduledTaskCapabilityVO> result = controller.get();

        assertTrue(result.isSuccess());
        assertFalse(result.getData().isAvailable());
        assertEquals("V037_PARTIAL", result.getData().getMode());
        assertTrue(result.getData().isClusterReady());
        assertEquals("DATABASE_UPGRADE_REQUIRED", result.getData().getReason());
        RequestMapping mapping = ScheduledTaskCapabilityController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[]{"/api/capabilities/scheduled-task"}, mapping.value());
        assertTrue(ScheduledTaskCapabilityController.class.getMethod("get")
                .isAnnotationPresent(GetMapping.class));
        RequireWorkspaceAccess access = ScheduledTaskCapabilityController.class
                .getAnnotation(RequireWorkspaceAccess.class);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, access.value());
        assertNull(ScheduledTaskCapabilityController.class
                .getAnnotation(RequiresScheduledTaskCapability.class));
    }

    @Test
    void defaultPropertiesFailClosed() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();

        assertFalse(properties.isEnabled());
        assertFalse(properties.isScannerEnabled());
        assertFalse(properties.isClusterReadyAttestation());
    }

    @Test
    void bindsAllThreeDeploymentEnvironmentSettings() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "ainative.scheduled-task.enabled", "true",
                "ainative.scheduled-task.scanner-enabled", "true",
                "ainative.scheduled-task.cluster-ready-attestation", "true")));

        ScheduledTaskProperties properties = Binder.get(environment).bind(
                "ainative.scheduled-task", Bindable.of(ScheduledTaskProperties.class)).get();

        assertTrue(properties.isEnabled());
        assertTrue(properties.isScannerEnabled());
        assertTrue(properties.isClusterReadyAttestation());
    }

    private ScheduledTaskCapabilityGuard guard(V037SchemaMode mode, boolean schema,
                                                boolean module, boolean cluster) {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(module);
        properties.setClusterReadyAttestation(cluster);
        V037SchemaCapability capability = new V037SchemaCapability(
                mode, schema ? V037MapperMode.SOURCE_AWARE : V037MapperMode.LEGACY,
                schema, schema, false, Set.of("scheduled_task"), Instant.EPOCH);
        return new ScheduledTaskCapabilityGuard(capability, properties,
                new V037CompatibilityMetrics(new MetricRegistry(), capability));
    }
}
