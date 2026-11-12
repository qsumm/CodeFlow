package com.ainative.integration.provider;

/**
 * 外部工单平台访问配置(provider 无关):baseUrl + 集成账号 + 密钥/密码 + 可选的
 * 平台特定区域标识(仅部分平台使用,其余为 null)。
 */
public record ExternalPlatformConfig(String baseUrl, String clientKey, String accessSecret, String regionId,
                                     String externalProjectId) {
    public ExternalPlatformConfig(String baseUrl, String clientKey, String accessSecret, String regionId) {
        this(baseUrl, clientKey, accessSecret, regionId, null);
    }
}
