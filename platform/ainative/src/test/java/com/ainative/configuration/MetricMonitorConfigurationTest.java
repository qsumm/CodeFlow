package com.ainative.configuration;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricMonitorConfigurationTest {

    @Test
    void registryCarriesJvmAndThreadPoolMetricsWithoutNativeDependencies() {
        MetricRegistry registry = new MetricMonitorConfiguration().getMetricRegistry();

        assertTrue(registry.getMetrics().containsKey("jvm.heap.used"));
        assertTrue(registry.getMetrics().keySet().stream().anyMatch(name -> name.startsWith("sys.active_count")
                || name.startsWith("sys.pool_size")));
        assertTrue(registry.getMetrics().containsKey("mtrs_cnt"));
    }

    @Test
    void retiredSystemAndRemoteMetricsAreGone() {
        MetricRegistry registry = new MetricMonitorConfiguration().getMetricRegistry();

        assertFalse(registry.getMetrics().keySet().stream().anyMatch(name -> name.contains("mem_total")));
        assertFalse(registry.getMetrics().keySet().stream().anyMatch(name -> name.startsWith("sys.load")));
        assertFalse(registry.getMetrics().keySet().stream().anyMatch(name -> name.contains("disk_")));
    }
}
