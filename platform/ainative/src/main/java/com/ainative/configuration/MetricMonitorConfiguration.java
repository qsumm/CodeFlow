package com.ainative.configuration;

import com.ainative.log.metric.ExceptionMetricReporter;
import com.ainative.log.metric.RpcInvokeExtendMetricReporter;
import com.ainative.log.metric.monitor.BizThreadPoolGaugeSet;
import com.ainative.log.metric.monitor.ThreadPoolGaugeSet;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.lang.management.ManagementFactory;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 指标注册表:JVM / 线程池 / 业务指标(进程内观测)。
 *
 * 系统级指标(CPU/内存/磁盘/网络)与云端上报链路已随合规清理退役:
 * 社区构建不携带 GPL 组件,也不依赖云端日志服务;MetricRegistry 仍被
 * 业务指标类注入(DispatchLiveActivityMetrics / ScheduledTaskMetrics 等)。
 */
@Configuration
public class MetricMonitorConfiguration {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired(required = false)
    private ServletWebServerApplicationContext applicationContext;

    @Primary
    @Bean(name = "ainativeMonitorRegistry")
    public MetricRegistry getMetricRegistry() {
        MetricRegistry registry = new MetricRegistry();

        registry.gauge("mtrs_cnt", () -> (Gauge<Integer>) () -> registry.getMetrics().size());

        // JVM
        registry.registerAll("jvm", new MemoryUsageGaugeSet());
        registry.registerAll("jvm", new GarbageCollectorMetricSet());
        registry.registerAll("jvm", new ClassLoadingGaugeSet());
        registry.registerAll("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));

        // web tomcat thread pool
        registry.registerAll("sys", new ThreadPoolGaugeSet(applicationContext));

        // business thread pool
        BizThreadPoolGaugeSet bizThreadPoolGaugeSet = new BizThreadPoolGaugeSet(registry);
        bizThreadPoolGaugeSet.register("AsyncTaskThreadPool", (ThreadPoolExecutor) ThreadPoolManager.asyncTaskThreadPool);
        bizThreadPoolGaugeSet.register("MetricCallBackThreadPool", (ThreadPoolExecutor) ThreadPoolManager.metricCallBackExecutor);

        ExceptionMetricReporter.setMetricRegistry(registry);
        RpcInvokeExtendMetricReporter.setMetricRegistry(registry);

        logger.info("Metric registry initialized (JVM/pools/business metrics; remote export retired)");
        return registry;
    }
}
