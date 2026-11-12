package com.ainative.integration.provider;

/** 外部工单平台调用异常;terminal=true 表示重试也不会成功的业务拒绝(4xx 类)。 */
public class ExternalPlatformException extends RuntimeException {

    private final boolean terminal;

    public ExternalPlatformException(String message) {
        this(message, false);
    }

    public ExternalPlatformException(String message, boolean terminal) {
        super(message);
        this.terminal = terminal;
    }

    public ExternalPlatformException(String message, Throwable cause) {
        super(message, cause);
        this.terminal = false;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
