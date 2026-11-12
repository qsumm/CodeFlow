package com.ainative.mcp;

import com.ainative.auth.jwt.JwtService;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.dispatch.DispatchDO;
import com.ainative.dispatch.DispatchDao;
import com.ainative.dispatch.DispatchStatus;
import com.ainative.dispatch.ExecutionSourceType;
import com.ainative.workspace.WorkspaceMemberDO;
import com.ainative.workspace.WorkspaceMemberDao;
import com.ainative.workitem.WorkitemDO;
import com.ainative.workitem.WorkitemDao;
import com.ainative.scheduledtask.ScheduledTaskRunDao;
import com.ainative.scheduledtask.ScheduledTaskRunDO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class DispatchMcpTokenService {
    static final String PREFIX = "ainativedispatch_";
    private static final String PURPOSE = "dispatch-mcp";
    private static final long TTL_SECONDS = 24 * 60 * 60;
    private static final Set<String> ACTIVE = Set.of("PACKAGING", "PENDING", "DISPATCHED", "ACKED", "RUNNING", "PAUSING");
    private static final Set<String> MEMORY_TERMINAL = Set.of(DispatchStatus.SUCCEEDED);

    private com.ainative.dispatch.DispatchRecoveryService recovery;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRecovery(com.ainative.dispatch.DispatchRecoveryService service) { recovery = service; }
    private final JwtService jwtService;
    private final DispatchDao dispatchDao;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final ScheduledTaskRunDao scheduledTaskRunDao;

    public DispatchMcpTokenService(JwtService jwtService, DispatchDao dispatchDao,
                                   WorkitemDao workitemDao, WorkspaceMemberDao workspaceMemberDao) {
        this(jwtService, dispatchDao, workitemDao, workspaceMemberDao, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DispatchMcpTokenService(JwtService jwtService, DispatchDao dispatchDao,
                                   WorkitemDao workitemDao, WorkspaceMemberDao workspaceMemberDao,
                                   ScheduledTaskRunDao scheduledTaskRunDao) {
        this.jwtService = jwtService;
        this.dispatchDao = dispatchDao;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.scheduledTaskRunDao = scheduledTaskRunDao;
    }

    public String issue(DispatchDO dispatch) {
        long userId = dispatch.getCreatorId() == null ? 0L : dispatch.getCreatorId();
        if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                && scheduledTaskRunDao != null) {
            ScheduledTaskRunDO run = scheduledTaskRunDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
            if (run != null && dispatch.getTenantId().equals(run.getWorkspaceId())
                    && run.getOwnerId() != null && run.getOwnerId() > 0) {
                userId = run.getOwnerId();
            }
        }
        if (userId <= 0 && workitemDao != null) {
            WorkitemDO workitem = workitemDao.findById(dispatch.getWorkitemId());
            if (workitem != null && dispatch.getTenantId().equals(workitem.getTenantId())) {
                if (workitem.getCreatorId() != null && workitem.getCreatorId() > 0) {
                    userId = workitem.getCreatorId();
                } else if (workitem.getAssignOperatorId() != null && workitem.getAssignOperatorId() > 0) {
                    userId = workitem.getAssignOperatorId();
                }
            }
        }
        if (userId <= 0) {
            throw new IllegalStateException("dispatch MCP principal is unavailable for dispatch " + dispatch.getId());
        }
        return PREFIX + jwtService.signScoped(userId, dispatch.getTenantId(), PURPOSE, dispatch.getId(), TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        DispatchPrincipal principal = authenticateDispatch(token);
        return new McpAccessTokenService.Principal(
                principal.workspaceId(), principal.userId(), -principal.dispatchId(), principal.accessLevel(),
                McpAccessTokenService.CredentialType.DISPATCH);
    }

    public DispatchPrincipal authenticateDispatch(String token) {
        return authenticateDispatch(token, false);
    }

    /**
     * Memory synchronization or mandatory index repair may finish just after a dispatch is
     * acknowledged. The signed dispatch capability remains agent/workspace scoped and expires
     * after 24 hours, but unlike general MCP access it survives RUNNING -> SUCCEEDED for
     * already-acquired lease continuation. Other terminal states stay rejected.
     */
    public DispatchPrincipal authenticateMemoryDispatch(String token) {
        return authenticateDispatch(token, true);
    }

    private DispatchPrincipal authenticateDispatch(String token, boolean allowMemoryTerminal) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            Map<String, Object> claims = jwtService.parseScoped(token.substring(PREFIX.length()));
            if (!PURPOSE.equals(claims.get("purpose"))) {
                throw new IllegalArgumentException("invalid purpose");
            }
            long dispatchId = ((Number) claims.get("subjectId")).longValue();
            long workspaceId = ((Number) claims.get("workspace")).longValue();
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || !workspaceIdEquals(dispatch, workspaceId)
                    || (!ACTIVE.contains(dispatch.getStatus())
                    && !(allowMemoryTerminal && MEMORY_TERMINAL.contains(dispatch.getStatus())))
                    || (recovery != null && recovery.fenced(dispatch))) {
                throw new IllegalArgumentException("dispatch is inactive");
            }
            long userId = ((Number) claims.get("uid")).longValue();
            WorkspaceAccessLevel level = resolveAccessLevel(workspaceId, userId);
            return new DispatchPrincipal(workspaceId, userId, dispatchId, dispatch.getAgentId(), level);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record DispatchPrincipal(long workspaceId, long userId, long dispatchId, Long agentId,
                                    WorkspaceAccessLevel accessLevel) {
    }

    private boolean workspaceIdEquals(DispatchDO dispatch, long workspaceId) {
        return dispatch.getTenantId() != null && dispatch.getTenantId() == workspaceId;
    }

    private WorkspaceAccessLevel resolveAccessLevel(long workspaceId, long userId) {
        if (workspaceMemberDao == null) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getAccessLevel() == null) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
        try {
            return WorkspaceAccessLevel.valueOf(member.getAccessLevel());
        } catch (IllegalArgumentException e) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
    }
}
