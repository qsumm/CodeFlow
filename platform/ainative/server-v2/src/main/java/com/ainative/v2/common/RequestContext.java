package com.ainative.v2.common;

/** 请求级上下文:当前用户(由 AuthFilter 注入)。 */
public final class RequestContext {
    private static final ThreadLocal<Long> UID = new ThreadLocal<>();
    private static final ThreadLocal<Long> WORKSPACE = new ThreadLocal<>();

    private RequestContext() {}

    public static void setUserId(Long uid) {
        UID.set(uid);
    }

    public static Long userId() {
        return UID.get();
    }

    public static void setWorkspaceId(Long workspaceId) {
        WORKSPACE.set(workspaceId);
    }

    /** 当前工作区:令牌未带 workspace 时回退到 1(单工作区兼容)。 */
    public static long workspaceId() {
        Long ws = WORKSPACE.get();
        return ws == null ? 1L : ws;
    }

    public static long requireUserId() {
        Long uid = UID.get();
        if (uid == null) {
            throw new BizException("10401", "未登录或登录已失效");
        }
        return uid;
    }

    public static void clear() {
        UID.remove();
        WORKSPACE.remove();
    }
}
