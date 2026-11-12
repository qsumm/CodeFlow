package com.ainative.context;


import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.log.BizLog;
import com.ainative.workspace.WorkspaceMemberDO;

public class RequestContext {

    private RequestContext() {}

    private static ThreadLocal<RequestContext> threadLocalContext = ThreadLocal.withInitial(RequestContext::new);

    private String requestId;

    private String traceId;

    private String operation;

    private Long userId;

    private Long currentWorkspaceId;

    private WorkspaceAccessLevel workspaceAccessLevel;

    private WorkspaceMemberDO workspaceMember;

    private Boolean debug;

    private BizLog bizLog;

    private ResponseContext responseContext = new ResponseContext();

    public static RequestContext get() {
        return threadLocalContext.get();
    }

    public static void destroy() {
        threadLocalContext.remove();
    }

    public static void setContext(RequestContext context) {
        threadLocalContext.set(context);
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public BizLog getBizLog() {
        return bizLog;
    }

    public void setBizLog(BizLog bizLog) {
        this.bizLog = bizLog;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getCurrentWorkspaceId() {
        return currentWorkspaceId;
    }

    public void setCurrentWorkspaceId(Long currentWorkspaceId) {
        this.currentWorkspaceId = currentWorkspaceId;
    }

    public WorkspaceAccessLevel getWorkspaceAccessLevel() {
        return workspaceAccessLevel;
    }

    public void setWorkspaceAccessLevel(WorkspaceAccessLevel workspaceAccessLevel) {
        this.workspaceAccessLevel = workspaceAccessLevel;
    }

    public WorkspaceMemberDO getWorkspaceMember() {
        return workspaceMember;
    }

    public void setWorkspaceMember(WorkspaceMemberDO workspaceMember) {
        this.workspaceMember = workspaceMember;
    }

    public void setDebug(String debug) {
        this.debug = Boolean.valueOf(debug);
    }

    public Boolean getDebug() {
        return debug;
    }

    public ResponseContext getResponseContext() {
        return responseContext;
    }
}
