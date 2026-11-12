package com.ainative.v2.ex;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 通知偏好(/api/notifications/prefs)。 */
@RestController
@RequestMapping("/api/notifications/prefs")
public class NotifyPrefController {
    public record PrefRequest(List<Map<String, Object>> items) {}

    private final ExService service;

    public NotifyPrefController(ExService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(service.prefs(RequestContext.requireUserId()));
    }

    @PutMapping
    public ApiResult<Void> save(@RequestBody PrefRequest req) {
        service.savePrefs(RequestContext.requireUserId(), req.items());
        return ApiResult.ok(null);
    }
}
