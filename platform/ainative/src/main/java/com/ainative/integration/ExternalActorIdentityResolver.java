package com.ainative.integration;

import com.ainative.agent.AgentDO;
import com.ainative.agent.AgentDao;
import com.ainative.user.UserAccountStates;
import com.ainative.user.UserDO;
import com.ainative.user.UserDao;
import org.springframework.stereotype.Component;

@Component
public class ExternalActorIdentityResolver {

    private final UserDao userDao;
    private final AgentDao agentDao;

    public ExternalActorIdentityResolver(UserDao userDao, AgentDao agentDao) {
        this.userDao = userDao;
        this.agentDao = agentDao;
    }

    public Identity resolve(String actorType, Long actorRef) {
        if ("AGENT".equals(actorType) && actorRef != null) {
            AgentDO agent = agentDao.findById(actorRef);
            String name = agent == null ? null : firstNonBlank(agent.getName(), "AI 成员");
            return new Identity(firstNonBlank(name, "AI 成员"), "Agent: " + firstNonBlank(name, "AI 成员") + "（ID: " + actorRef + "）");
        }
        if ("HUMAN".equals(actorType) && actorRef != null) {
            UserDO user = userDao.findByIdIncludingDeleted(actorRef);
            String name = user == null ? null : UserAccountStates.displayName(user);
            return new Identity(firstNonBlank(name, "用户"), "用户: " + firstNonBlank(name, "用户") + "（ID: " + actorRef + "）");
        }
        return new Identity("系统", "CodeFlow 系统");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record Identity(String displayName, String sourceText) {
    }
}
