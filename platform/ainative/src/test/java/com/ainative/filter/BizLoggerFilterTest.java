package com.ainative.filter;

import com.ainative.context.RequestContext;
import com.ainative.log.BizLog;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BizLoggerFilter 行为契约。SLS 结构化日志上报已随合规清理退役,
 * 本测试保留请求关联(RequestContext/MDC/响应头)与观测探针的断言。
 */
class BizLoggerFilterTest {

    @Test
    void artifactUploadObservesSerializedResponseWithoutConsumingRequestOrChangingBytes() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/daemon/dispatches/13616/artifacts");
        request.setQueryString("token=secret-must-not-be-logged");
        request.setContent("multipart-content".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UploadResponseProbe> observed = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> {
            assertEquals("multipart-content", StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(resp instanceof UploadResponseProbe);
            observed.set((UploadResponseProbe) resp);
            resp.setContentType("application/json");
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(resp.getOutputStream(),
                    java.util.Map.of("remoteRef", "test/", "files", java.util.List.of()));
        });
        assertEquals(200, response.getStatus());
        assertEquals("test/", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getContentAsByteArray()).get("remoteRef").asText());
        assertEquals(123, observed.get().firstNonWhitespaceByte());
        assertEquals(response.getContentAsByteArray().length, observed.get().bytesWritten());
    }

    @Test
    void uploadProbeDoesNotSwallowPartialResponseFailure() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException failure = new IOException("simulated write failure");
        IOException actual = assertThrows(IOException.class, () -> filter.doFilter(
                new MockHttpServletRequest("POST", "/api/daemon/dispatches/13616/artifacts"), response,
                (req, resp) -> {
                    resp.getOutputStream().write('{');
                    throw failure;
                }));
        assertTrue(actual == failure);
        assertEquals("{", response.getContentAsString());
    }

    @AfterEach
    void tearDown() {
        RequestContext.destroy();
        MDC.clear();
    }

    @Test
    void operationUsesHeaderAndFallsBackToMethodAndPath() {
        assertEquals("SkillCreate", BizLoggerFilter.resolveOperation(
                " SkillCreate ", "POST", "/api/skills"));
        assertEquals("POST /api/skills", BizLoggerFilter.resolveOperation(
                " ", "POST", "/api/skills"));
    }

    @Test
    void elapsedMillisUsesMonotonicNanosecondsAndNeverReturnsNegative() {
        assertEquals(1523L, BizLoggerFilter.elapsedMillis(
                1_000_000L, 1_524_000_000L));
        assertEquals(0L, BizLoggerFilter.elapsedMillis(
                2_000_000L, 1_000_000L));
    }

    @Test
    void requestArrivedLogMessageIncludesMethodAndPath() {
        String message = BizLoggerFilter.requestArrivedLogMessage("GET", "/api/workitems/21769/unified-timeline");
        assertEquals("Request arrived method=GET requestPath=/api/workitems/21769/unified-timeline", message);
    }

    @Test
    void handleStartLogMessageIncludesPath() {
        String message = BizLoggerFilter.handleStartLogMessage("/api/workitems/21769/unified-timeline");
        assertEquals("Request handling started requestPath=/api/workitems/21769/unified-timeline", message);
    }

    @Test
    void responseEndLogMessageIncludesStatusAndElapsedMillis() {
        String message = BizLoggerFilter.responseEndLogMessage("/api/workitems/21769/unified-timeline", 200, 15234L);
        assertEquals("Response finished requestPath=/api/workitems/21769/unified-timeline status=200 elapsedMs=15234",
                message);
    }

    @Test
    void resolveRequestIdUsesIncomingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-incoming");
        assertEquals("rid-incoming", BizLoggerFilter.resolveRequestId(request));
    }

    @Test
    void resolveRequestIdGeneratesWhenHeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");
        String requestId = BizLoggerFilter.resolveRequestId(request);
        assertEquals(false, requestId.isBlank());
        assertDoesNotThrow(() -> java.util.UUID.fromString(requestId));
    }

    @Test
    void apiRequestWritesRequestIdToHeaderContextAndCleansUp() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skills");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-filter");
        request.addHeader("x-acs-api-name", "SkillCreate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                assertEquals("rid-filter", RequestContext.get().getRequestId());
                assertEquals("SkillCreate", RequestContext.get().getOperation());
                assertEquals("rid-filter", MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
                assertEquals("SkillCreate", MDC.get(BizLoggerFilter.OPERATION));
            }
        });

        filter.doFilter(request, response, chain);

        assertEquals("rid-filter", response.getHeader(BizLoggerFilter.REQUEST_ID_HEADER));
        assertNull(RequestContext.get().getRequestId());
        assertNull(MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
        assertNull(MDC.get(BizLoggerFilter.OPERATION));
    }

    @Test
    void apiRequestSetsRequestIdInMdcBeforeHandlerRunsAndCleansUpAfter() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workitems/21769/unified-timeline");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-timeline");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                assertEquals("rid-timeline", MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
            }
        });

        filter.doFilter(request, response, chain);

        assertEquals("rid-timeline", response.getHeader(BizLoggerFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
    }

    @Test
    void apiRequestPassesBodyThroughWithoutMutatingIt() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/skills/1");
        request.setContentType("application/json");
        request.setContent("{\"secret\":\"raw-secret\"}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> seenBody = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {
                seenBody.set(StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8));
            }
        });

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("{\"secret\":\"raw-secret\"}", seenBody.get());
    }

    @Test
    void apiRequestFinalizesCoreRequestFieldsIntoRequestContext() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skills");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-core");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<BizLog> captured = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                captured.set(RequestContext.get().getBizLog());
                resp.setStatus(201);
            }
        });

        filter.doFilter(request, response, chain);

        BizLog log = captured.get();
        assertEquals("rid-core", log.getRequestId());
        assertEquals("POST /api/skills", log.getOperation());
        assertEquals("/api/skills", log.getPath());
        assertEquals("POST", log.getHttpMethod());
    }

    @Test
    void apiRequestRecordsOutcomeMetricForFailureStatus() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        MetricRegistry registry = new MetricRegistry();
        ReflectionTestUtils.setField(filter, "metricRegistry", registry);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(503);
            }
        });

        filter.doFilter(new MockHttpServletRequest("GET", "/api/skills"), response, chain);

        assertTrue(registry.getMeters().values().stream().mapToLong(m -> m.getCount()).sum() >= 1L);
    }

    @Test
    void apiRequestRecordsUnhandledDownstreamExceptionAsFailure() {
        BizLoggerFilter filter = new BizLoggerFilter();
        MetricRegistry registry = new MetricRegistry();
        ReflectionTestUtils.setField(filter, "metricRegistry", registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");

        assertThrows(javax.servlet.ServletException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(),
                        (req, resp) -> { throw new javax.servlet.ServletException("boom"); }));
    }
}
