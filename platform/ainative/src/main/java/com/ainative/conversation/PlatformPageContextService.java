package com.ainative.conversation;

import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.json.JSON;
import com.ainative.mcp.McpAccessTokenService;
import com.ainative.mcp.McpToolService;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only route identity is accepted from the browser; resource details are loaded under the owner identity. */
@Service
public class PlatformPageContextService {
    private final McpToolService tools;
    public PlatformPageContextService(McpToolService tools) { this.tools = tools; }

    public String snapshot(long workspaceId, long userId, long conversationId, String path) {
        if (path == null || path.length() > 256 || !path.matches("/[a-z0-9/-]*")) return null;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workspaceId", workspaceId);
        context.put("path", path);
        String[] parts = path.split("/");
        Map<String, String> readers = Map.of("workitems", "ainative.get_workitem",
                "agents", "ainative.get_agent", "sdlcs", "ainative.get_sdlc",
                "repos", "ainative.get_repo", "skills", "ainative.get_skill",
                "scheduled-tasks", "ainative.get_scheduled_task",
                "scheduled-task-runs", "ainative.get_scheduled_task_run");
        if (parts.length >= 3 && readers.containsKey(parts[1]) && parts[2].matches("[1-9][0-9]{0,17}")) {
            long id = Long.parseLong(parts[2]);
            var principal = new McpAccessTokenService.Principal(workspaceId, userId, conversationId,
                    WorkspaceAccessLevel.READ_ONLY, McpAccessTokenService.CredentialType.CONVERSATION);
            Object resource = tools.call(principal, readers.get(parts[1]), Map.of("workspaceId", workspaceId, "id", id));
            Map<String, Object> raw = JSON.parseObject(JSON.toJSONString(resource));
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String key : new String[]{"id", "title", "name", "status", "workType", "assigneeType", "assigneeRef", "contentMd", "description"}) {
                Object value = raw.get(key);
                if (value != null) summary.put(key, value instanceof String text && text.length() > 3000
                        ? text.substring(0, 3000) : value);
            }
            context.put("resourceType", parts[1]); context.put("resourceId", id); context.put("resource", summary);
        }
        return JSON.toJSONString(Map.of("platformPageContext", context));
    }
}
