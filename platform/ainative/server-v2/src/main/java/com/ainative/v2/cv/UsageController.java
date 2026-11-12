package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workitems/{workitemId}/ai-usage")
public class UsageController {
    private final CvService service;

    public UsageController(CvService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> usage(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.usage(workitemId));
    }
}
