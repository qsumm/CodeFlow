package com.ainative.integration.receipt;

import java.util.Arrays;
import java.util.stream.Collectors;

public final class ExternalOperationKeys {

    private ExternalOperationKeys() {
    }

    /** 状态回写操作键:{provider}.status:{nodeId}(同节点幂等)。 */
    public static String status(String provider, long workitemId, long toNodeId) {
        String normalized = provider == null || provider.isBlank() ? "external" : provider.trim().toLowerCase();
        return semantic(normalized + ".status", workitemId, toNodeId, "v2");
    }

    /** 按 provider 归一的备注操作键:{provider}.comment。 */
    public static String comment(String provider, long sourceWorkitemId, long commentId) {
        String normalized = provider == null || provider.isBlank() ? "external" : provider.trim().toLowerCase();
        return semantic(normalized + ".comment", sourceWorkitemId, commentId);
    }

    public static String marker(String operationKey) {
        return "<!-- an-op:" + ExternalOperationDigests.sha256(operationKey).substring(0, 24) + " -->";
    }

    private static String semantic(String prefix, Object... parts) {
        String material = Arrays.stream(parts)
                .map(value -> value == null ? "" : String.valueOf(value).trim())
                .collect(Collectors.joining("\u0000"));
        return prefix + ":" + ExternalOperationDigests.sha256(material);
    }
}
