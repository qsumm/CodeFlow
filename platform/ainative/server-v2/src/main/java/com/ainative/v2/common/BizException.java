package com.ainative.v2.common;

/** 业务异常:code 与 v1 保持兼容(如 10401 未登录、10403 无权限)。 */
public class BizException extends RuntimeException {
    private final String code;

    public BizException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
