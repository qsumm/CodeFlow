package com.ainative.memory.store;

import com.ainative.access.RequireWorkspaceAccess;
import com.ainative.access.WorkspaceAccessLevel;
import com.ainative.common.error.BizException;
import com.ainative.common.error.ErrorCode;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/memory-stores/directory")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看记忆归属", actionKey = "access.action.view_memory_ownership")
public class MemoryDirectoryController {
    private final MemoryDirectoryService directory;
    public MemoryDirectoryController(MemoryDirectoryService directory) { this.directory = directory; }

    @GetMapping
    public Result<MemoryDirectoryService.Directory> list() {
        var context = RequestContext.get();
        if (context.getCurrentWorkspaceId() == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        if (context.getUserId() == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return Result.ok(directory.list(context.getCurrentWorkspaceId(), context.getUserId(),
                context.getWorkspaceAccessLevel() == WorkspaceAccessLevel.ADMIN));
    }
}
