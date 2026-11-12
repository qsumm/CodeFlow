package com.ainative.agent;

import com.ainative.json.JSON;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为工作空间播种出厂平台数字人 "AI 管家"（kind=PLATFORM，直接 ONLINE）。幂等：已存在则跳过。 */
@Component
public class PlatformAgentSeeder {

    public static final String PLATFORM_KIND = "PLATFORM";
    public static final String PLATFORM_AGENT_NAME = "AI 管家";
    /** 出厂命名迁移:老库(≤ Chief of Staff 时代)重启时自动跟随改名,见 seed。 */
    public static final String LEGACY_AGENT_NAME = "Chief of Staff";
    public static final String PLATFORM_ROLE_CODE = "chief_of_staff";

    static final String AGENT_MD_PATH = "agents/chief_of_staff_agent.md";
    static final String SOUL_MD_PATH = "agents/chief_of_staff_soul.md";

    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;

    public PlatformAgentSeeder(AgentDao agentDao, AgentVersionDao versionDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
    }

    /** @return true 当且仅当本次调用实际创建了平台数字人。 */
    public boolean seed(long tenantId, long creatorId) {
        AgentDO existing = agentDao.findPlatformAgent(tenantId);
        if (existing != null) {
            // 出厂命名迁移:存量库的平台数字人仍用旧名(Chief of Staff)时,重启自动改名。
            // 只更新 agent.name 与各 APPROVED 版本的 roleName/identityJson/文档内容,不新建版本。
            if (LEGACY_AGENT_NAME.equals(existing.getName())) {
                renameLegacy(existing, tenantId, creatorId);
            }
            return false;
        }
        String agentMd = loadTemplate(AGENT_MD_PATH);
        String soulMd = loadTemplate(SOUL_MD_PATH);

        AgentDO agent = new AgentDO();
        agent.setTenantId(tenantId);
        agent.setName(PLATFORM_AGENT_NAME);
        agent.setKind(PLATFORM_KIND);
        agent.setStatus("ONLINE");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(creatorId);
        agent.setVersion(0);
        agentDao.insert(agent);

        AgentVersionDO version = new AgentVersionDO();
        version.setTenantId(tenantId);
        version.setAgentId(agent.getId());
        version.setVersionNo(1);
        version.setStatus("APPROVED");
        version.setRoleName(PLATFORM_AGENT_NAME);
        version.setRoleCode(PLATFORM_ROLE_CODE);
        version.setResponsibilities(agentMd);
        version.setBusinessBackground(soulMd);
        version.setSdlcId(null);
        version.setIdentityJson(buildIdentityJson(agentMd, soulMd));
        version.setReviewComment("platform seeded");
        version.setReviewedAt(new Date());
        version.setCreatorId(creatorId);
        version.setVersion(0);
        versionDao.insert(version);

        agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                version.getId(), null, 1, agent.getVersion(), creatorId);
        return true;
    }

    /** 与 AgentService#buildIdentityJson 输出同构（evolutionMode 默认 ASSISTED）。 */
    private String buildIdentityJson(String agentMd, String soulMd) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", PLATFORM_AGENT_NAME);
        map.put("avatarUrl", null);
        map.put("roleName", PLATFORM_AGENT_NAME);
        map.put("roleCode", PLATFORM_ROLE_CODE);
        map.put("businessBackground", soulMd);
        map.put("responsibilities", agentMd);
        map.put("evolutionMode", "ASSISTED");
        return JSON.toJSONString(map);
    }

    /** 存量库改名:agent.name 与所有 APPROVED 版本里出现的旧名一并替换(乐观锁各取各的 version)。 */
    private void renameLegacy(AgentDO agent, long tenantId, long creatorId) {
        agentDao.updateName(agent.getId(), tenantId, PLATFORM_AGENT_NAME, agent.getVersion(), creatorId);
        for (AgentVersionDO version : versionDao.listApprovedByAgent(agent.getId())) {
            String roleName = renameLegacyInText(version.getRoleName());
            String identityJson = renameLegacyInText(version.getIdentityJson());
            String responsibilities = renameLegacyInText(version.getResponsibilities());
            String background = renameLegacyInText(version.getBusinessBackground());
            versionDao.updateConfig(version.getId(), tenantId, roleName, version.getRoleCode(),
                    background, responsibilities, version.getSdlcId(), identityJson,
                    version.getVersion(), creatorId);
        }
    }

    private String renameLegacyInText(String text) {
        return text == null ? null : text.replace(LEGACY_AGENT_NAME, PLATFORM_AGENT_NAME);
    }

    protected String loadTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException("platform agent template is blank: " + path);
            }
            return content;
        } catch (IOException ex) {
            throw new IllegalStateException("platform agent template missing: " + path, ex);
        }
    }
}
