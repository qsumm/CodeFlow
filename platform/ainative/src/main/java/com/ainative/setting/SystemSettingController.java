package com.ainative.setting;

import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.setting.dto.SettingVO;
import com.ainative.setting.dto.UpdateSettingsRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理系统设置", actionKey = "access.action.manage_system_settings")
public class SystemSettingController {

    private final SystemSettingService settingService;

    public SystemSettingController(SystemSettingService settingService) {
        this.settingService = settingService;
    }

    @GetMapping("/{group}")
    public Result<List<SettingVO>> listByGroup(@PathVariable("group") String group) {
        return Result.ok(settingService.listByGroup(group, currentWorkspaceId()));
    }

    @PutMapping("/{group}")
    public Result<Void> updateGroup(@PathVariable("group") String group,
                                     @RequestBody UpdateSettingsRequest req) {
        settingService.updateGroup(group, req, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = RequestContext.get().getUserId();
        if (uid == null) { throw new BizException(ErrorCode.UNAUTHORIZED); }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = RequestContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) { throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER); }
        return workspaceId;
    }
}
