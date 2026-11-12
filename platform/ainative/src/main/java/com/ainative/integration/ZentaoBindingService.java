package com.ainative.integration;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.provider.PageResult;
import com.ainative.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 禅道绑定持久化(provider=ZENTAO)。与外部平台绑定共用 external_project_binding 表;
 * 凭证 = 集成账号密码(SecretCrypto 加密);region_id/writeback_staff_id 为上游平台专属列,禅道置空。
 */
@Service
public class ZentaoBindingService {

    public static final String PROVIDER = "ZENTAO";

    private final ExternalProjectBindingDao bindingDao;
    private final SecretCrypto secretCrypto;

    public ZentaoBindingService(ExternalProjectBindingDao bindingDao, SecretCrypto secretCrypto) {
        this.bindingDao = bindingDao;
        this.secretCrypto = secretCrypto;
    }

    /** 绑定请求:baseUrl=禅道根、account/Password=集成账号、externalProjectId=禅道产品 id。 */
    public record ZentaoBindingRequest(String baseUrl, String account, String password,
                                       String externalProjectId, String externalProjectName,
                                       Integer pollIntervalSeconds, Boolean enabled) { }

    public ExternalProjectBindingDO createBinding(ZentaoBindingRequest req, long tenantId, long userId) {
        validate(req);
        String externalProjectId = req.externalProjectId().trim();
        ExternalProjectBindingDO existing = bindingDao.findByProject(tenantId, PROVIDER, externalProjectId);
        if (existing != null) {
            return existing;
        }
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setTenantId(tenantId);
        binding.setProvider(PROVIDER);
        binding.setExternalProjectId(externalProjectId);
        binding.setExternalProjectName(req.externalProjectName());
        binding.setBaseUrl(req.baseUrl().trim());
        binding.setClientKey(defaultIfBlank(req.account(), "zentao-int"));
        binding.setCredentialRef(secretCrypto.encrypt(req.password().trim()));
        binding.setRegionId("1");
        binding.setPollIntervalSeconds(req.pollIntervalSeconds() == null ? 60 : req.pollIntervalSeconds());
        binding.setEnabled(Boolean.FALSE.equals(req.enabled()) ? 0 : 1);
        binding.setCreatorId(userId);
        bindingDao.insert(binding);
        return binding;
    }

    public List<ExternalProjectBindingDO> listBindings(long tenantId) {
        return bindingDao.list(tenantId, PROVIDER, 0, 100);
    }

    public ExternalProjectBindingDO requireBinding(long bindingId, long tenantId) {
        ExternalProjectBindingDO binding = bindingDao.findById(bindingId);
        if (binding == null || binding.getTenantId() != tenantId || !PROVIDER.equals(binding.getProvider())) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        return binding;
    }

    private void validate(ZentaoBindingRequest req) {
        if (req.baseUrl() == null || req.baseUrl().isBlank()
                || req.account() == null || req.account().isBlank()
                || req.password() == null || req.password().isBlank()
                || req.externalProjectId() == null || req.externalProjectId().isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
