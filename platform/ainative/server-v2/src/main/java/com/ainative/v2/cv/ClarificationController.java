package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 工作项澄清结论接口(与 v1 /api/workitems/{id}/clarification 对齐)。 */
@RestController
@RequestMapping("/api/workitems/{workitemId}/clarification")
public class ClarificationController {
    public record SaveRequest(String contentMd) {}

    private final CvService service;

    public ClarificationController(CvService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<Map<String, Object>> get(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.clarification(workitemId));
    }

    @PutMapping
    public ApiResult<Map<String, Object>> save(@PathVariable long workitemId, @RequestBody SaveRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.saveClarification(workitemId, req.contentMd()));
    }
}
