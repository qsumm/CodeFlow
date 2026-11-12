package com.ainative.mcp;

import com.ainative.json.JSON;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.mcp.dto.PlatformSkillVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PlatformSkillCatalog {
    private final List<PlatformSkillVO> skills = List.of(
            skill("ainative-workitem-operator", "CODEX_SKILL", "CodeFlow Workitem Operator",
                    "Create, inspect, update, assign, comment, pause, resume and transition AINative workitems through MCP tools.",
                    List.of("ainative.create_workitem", "ainative.list_workitems",
                            "ainative.get_workitem", "ainative.add_workitem_comment",
                            "ainative.list_workitem_comments", "ainative.update_workitem",
                            "ainative.update_workitem_tags",
                            "ainative.delete_workitem", "ainative.assign_workitem",
                            "ainative.transition_workitem", "ainative.pause_workitem",
                            "ainative.resume_workitem", "ainative.workitem_cli_upload_token",
                            "ainative.workitem_cli_download_token")),
            skill("ainative-sdlc-manager", "CODEX_SKILL", "CodeFlow Workflow Manager",
                    "Manage AINative workflows, steps, enablement and workitem status templates.",
                    List.of("ainative.create_sdlc", "ainative.list_sdlcs", "ainative.get_sdlc",
                            "ainative.update_sdlc", "ainative.delete_sdlc", "ainative.add_sdlc_step",
                            "ainative.update_sdlc_step", "ainative.delete_sdlc_step",
                            "ainative.reorder_sdlc_steps", "ainative.enable_sdlc",
                            "ainative.disable_sdlc", "ainative.list_status_templates",
                            "ainative.get_status_template")),
            skill("ainative-agent-manager", "CODEX_SKILL", "CodeFlow AI Member Manager",
                    "Create, update, list and inspect CodeFlow AI members from MCP clients.",
                    List.of("ainative.create_agent", "ainative.update_agent",
                            "ainative.list_agents", "ainative.get_agent")),
            skill("ainative-project-navigator", "CODEX_SKILL", "CodeFlow Project Navigator",
                    "List AINative projects available to the token owner before selecting an MCP workspace.",
                    List.of("ainative.list_projects")),
            skill("ainative-skill-manager", "CODEX_SKILL", "CodeFlow Skill Manager",
                    "Manage skills, MCP server records and plugin records, upload Skill packages, install reusable "
                            + "CodeFlow platform skills, and organize skills with project-level categories.",
                    List.of("ainative.list_platform_skills", "ainative.install_platform_skill",
                            "ainative.create_skill", "ainative.list_skills", "ainative.get_skill",
                            "ainative.update_skill", "ainative.delete_skill",
                            "ainative.inspect_skill_package", "ainative.upload_skill_package",
                            "ainative.create_skill_from_package", "ainative.update_skill_package",
                            "ainative.list_categories", "ainative.get_category",
                            "ainative.create_category", "ainative.update_category",
                            "ainative.delete_category", "ainative.set_skill_category",
                            "ainative.batch_set_skill_category")),
            skill("ainative-scheduled-task-operator", "CODEX_SKILL", "CodeFlow Scheduled Task Operator",
                    "Create, inspect, update, delete and transition 7x24 scheduled tasks and their runs through MCP tools.",
                    List.of("ainative.create_scheduled_task", "ainative.list_scheduled_tasks",
                            "ainative.get_scheduled_task", "ainative.update_scheduled_task",
                            "ainative.transition_scheduled_task", "ainative.delete_scheduled_task",
                            "ainative.get_scheduled_task_run", "ainative.list_scheduled_task_runs",
                            "ainative.add_scheduled_task_run_comment"))
    );

    public List<PlatformSkillVO> list() {
        return skills;
    }

    public PlatformSkillVO get(String id) {
        return skills.stream()
                .filter(skill -> skill.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND));
    }

    private static PlatformSkillVO skill(String id, String type, String name, String description, List<String> tools) {
        String spec = JSON.toJSONString(Map.of(
                "kind", "codex-skill",
                "id", id,
                "mcpServer", "ainative",
                "tools", tools,
                "instructions", description));
        return new PlatformSkillVO(id, type, name, description, spec);
    }
}
