package com.ainative.v2.cv;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.ainative.v2.common.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 澄清会话数据面接口(执行引擎属 ag/执行器子系统)。 */
@RestController
@RequestMapping("/api/agents/{agentId}/conversations")
public class ConversationController {
    public record CreateRequest(String title) {}
    public record TurnRequest(String contentMd) {}
    public record UsageRequest(Long workitemId, Long tokensIn, Long tokensOut, String model) {}

    private final CvService service;

    public ConversationController(CvService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@PathVariable long agentId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.conversations(agentId));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@PathVariable long agentId, @RequestBody(required = false) CreateRequest req) {
        return ApiResult.ok(service.createConversation(agentId, req == null ? null : req.title(),
                RequestContext.requireUserId()));
    }

    @GetMapping("/{conversationId}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long agentId, @PathVariable long conversationId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.conversation(conversationId));
    }

    @GetMapping("/{conversationId}/turns")
    public ApiResult<List<Map<String, Object>>> turns(@PathVariable long agentId, @PathVariable long conversationId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.turns(conversationId));
    }

    @PostMapping("/{conversationId}/turns")
    public ApiResult<Map<String, Object>> appendTurn(@PathVariable long agentId, @PathVariable long conversationId,
                                                     @RequestBody TurnRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.appendUserTurn(conversationId, req.contentMd()));
    }

    @GetMapping("/{conversationId}/events")
    public ApiResult<List<Map<String, Object>>> events(@PathVariable long agentId, @PathVariable long conversationId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.events(conversationId));
    }

    @PostMapping("/{conversationId}/usage")
    public ApiResult<Map<String, Object>> usage(@PathVariable long agentId, @PathVariable long conversationId,
                                               @RequestBody UsageRequest req) {
        RequestContext.requireUserId();
        if (req.workitemId() == null) {
            throw new BizException("10001", "workitemId 不能为空");
        }
        return ApiResult.ok(service.recordUsage(req.workitemId(),
                req.tokensIn() == null ? 0 : req.tokensIn(), req.tokensOut() == null ? 0 : req.tokensOut(),
                req.model()));
    }
}
