package com.ainative.ai;

import com.ainative.ai.dto.*;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/sessions")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看AI会话", actionKey = "access.action.view_ai_sessions")
public class AiSessionController {

    private static final Logger log = LoggerFactory.getLogger(AiSessionController.class);

    private final AiSessionService sessionService;

    public AiSessionController(AiSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建AI会话", actionKey = "access.action.create_an_ai_session")
    public Result<Long> create(@RequestBody CreateSessionRequest req) {
        long tenantId = RequestContext.get().getCurrentWorkspaceId();
        long userId = RequestContext.get().getUserId();
        log.info("ai session create scene={} bizRefType={} bizRefId={} userId={}", req.getScene(), req.getBizRefType(), req.getBizRefId(), userId);
        Long sessionId = sessionService.create(req, tenantId, userId);
        return Result.ok(sessionId);
    }

    @GetMapping("/{id}")
    public Result<AiSessionVO> get(@PathVariable Long id) {
        long tenantId = RequestContext.get().getCurrentWorkspaceId();
        log.info("ai session get id={} tenantId={}", id, tenantId);
        AiSessionVO vo = sessionService.get(id, tenantId);
        return Result.ok(vo);
    }

    @PostMapping("/{id}/messages")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "追加AI会话消息", actionKey = "access.action.append_an_ai_session_message")
    public Result<Void> appendMessage(@PathVariable Long id,
            @RequestBody AppendMessageRequest req) {
        long tenantId = RequestContext.get().getCurrentWorkspaceId();
        log.info("ai session appendMessage id={} tenantId={}", id, tenantId);
        sessionService.appendMessage(id, req, tenantId);
        return Result.ok(null);
    }

    @PostMapping("/{id}/confirm")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "确认AI会话", actionKey = "access.action.confirm_an_ai_session")
    public Result<Void> confirm(@PathVariable Long id,
            @RequestBody ConfirmResultRequest req) {
        long tenantId = RequestContext.get().getCurrentWorkspaceId();
        log.info("ai session confirm id={} tenantId={}", id, tenantId);
        sessionService.confirm(id, req, tenantId);
        return Result.ok(null);
    }

    @PostMapping("/{id}/cancel")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "取消AI会话", actionKey = "access.action.cancel_an_ai_session")
    public Result<Void> cancel(@PathVariable Long id) {
        long tenantId = RequestContext.get().getCurrentWorkspaceId();
        log.info("ai session cancel id={} tenantId={}", id, tenantId);
        sessionService.cancel(id, tenantId);
        return Result.ok(null);
    }
}
