package com.ainative.evolution;

import com.ainative.json.JSON;
import com.ainative.json.JSONObject;
import com.ainative.agent.AgentDO;
import com.ainative.agent.AgentDao;
import com.ainative.agent.AgentVersionDO;
import com.ainative.agent.AgentVersionDao;
import org.springframework.stereotype.Service;

@Service
public class EvolutionModeResolverLiteService {

    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;

    public EvolutionModeResolverLiteService(AgentDao agentDao, AgentVersionDao versionDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
    }

    public EvolutionMode resolve(long tenantId, long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getTenantId() == null || !agent.getTenantId().equals(tenantId)) {
            return EvolutionMode.ASSISTED;
        }
        Long versionId = agent.getOnlineVersionId() != null ? agent.getOnlineVersionId() : agent.getEditingVersionId();
        if (versionId == null) {
            return EvolutionMode.ASSISTED;
        }
        AgentVersionDO version = versionDao.findById(versionId);
        if (version == null || version.getTenantId() == null || !version.getTenantId().equals(tenantId)) {
            return EvolutionMode.ASSISTED;
        }
        return EvolutionMode.from(readEvolutionMode(version.getIdentityJson()));
    }

    private String readEvolutionMode(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) {
            return null;
        }
        try {
            JSONObject identity = JSON.parseObject(identityJson);
            return identity == null ? null : identity.getString("evolutionMode");
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
