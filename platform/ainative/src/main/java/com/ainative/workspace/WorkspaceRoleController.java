package com.ainative.workspace;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.workspace.dto.WorkspaceRoleRequest;
import com.ainative.workspace.dto.WorkspaceRoleVO;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspaces/current/roles")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN,
        action = "管理工作空间角色", actionKey = "access.action.manage_workspace_roles")
public class WorkspaceRoleController {
    private final WorkspaceRoleService roleService;

    public WorkspaceRoleController(WorkspaceRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
            action = "查看工作空间角色", actionKey = "access.action.view_workspace_roles")
    public Result<List<WorkspaceRoleVO>> list() {
        return Result.ok(roleService.list(RequestContext.get().getCurrentWorkspaceId()));
    }

    @PostMapping
    public Result<WorkspaceRoleVO> create(@RequestBody WorkspaceRoleRequest request) {
        return Result.ok(roleService.create(RequestContext.get().getCurrentWorkspaceId(), request,
                RequestContext.get().getUserId()));
    }

    @PutMapping("/{id}")
    public Result<WorkspaceRoleVO> update(@PathVariable("id") long id, @RequestBody WorkspaceRoleRequest request) {
        return Result.ok(roleService.update(RequestContext.get().getCurrentWorkspaceId(), id, request,
                RequestContext.get().getUserId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id, @RequestParam("version") int version) {
        roleService.delete(RequestContext.get().getCurrentWorkspaceId(), id, version,
                RequestContext.get().getUserId());
        return Result.ok(null);
    }
}
