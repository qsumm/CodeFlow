package com.ainative.v2.common;

/** 与 v1 一致的响应包络:{success, code, message, data}。 */
public record ApiResult<T>(boolean success, String code, String message, T data) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(true, "0", "", data);
    }

    public static <T> ApiResult<T> fail(String code, String message) {
        return new ApiResult<>(false, code, message, null);
    }
}
