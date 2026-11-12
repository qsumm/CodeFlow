package com.ainative.im;

import com.ainative.access.SystemAdminService;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.im.dto.GroupChatConfigVO;
import com.ainative.im.dto.PlatformImChannelConfigVO;
import com.ainative.im.dto.PlatformImSelectionVO;
import com.ainative.im.dto.SelectImChannelRequest;
import com.ainative.im.dto.UpdateDingTalkChannelRequest;
import com.ainative.im.dto.UpdateGroupChatConfigRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/platform/im-channels")
public class PlatformImChannelConfigController {
    private final PlatformImChannelConfigService configService;
    private final SystemAdminService systemAdminService;
    private final ImChannelRuntimeStatusService statusService;

    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService) {
        this(configService, systemAdminService, null);
    }

    @Autowired
    public PlatformImChannelConfigController(
            PlatformImChannelConfigService configService,
            SystemAdminService systemAdminService,
            ImChannelRuntimeStatusService statusService) {
        this.configService = configService;
        this.systemAdminService = systemAdminService;
        this.statusService = statusService;
    }

    @GetMapping
    public Result<List<PlatformImChannelConfigVO>> list() {
        return Result.ok(configService.list(RequestContext.get().getUserId()));
    }

    @PutMapping("/feishu")
    public Result<PlatformImChannelConfigVO> updateFeishu(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireSystemAdmin(RequestContext.get().getUserId(), "配置协作通知", "access.admin.im_denied");
        return Result.ok(configService.update(RequestContext.get().getUserId(), "FEISHU", request));
    }

    @PutMapping("/dingtalk")
    public Result<PlatformImChannelConfigVO> updateDingTalk(
            @Valid @RequestBody UpdateDingTalkChannelRequest request) {
        systemAdminService.requireSystemAdmin(RequestContext.get().getUserId(), "配置协作通知", "access.admin.im_denied");
        return Result.ok(configService.updateDingTalk(
                RequestContext.get().getUserId(), request));
    }

    @GetMapping("/selection")
    public Result<PlatformImSelectionVO> selection() {
        return Result.ok(configService.selection());
    }

    @PutMapping("/selection")
    public Result<PlatformImSelectionVO> selectProvider(
            @Valid @RequestBody SelectImChannelRequest request) {
        systemAdminService.requireSystemAdmin(RequestContext.get().getUserId(), "配置协作渠道", "access.admin.im_selection_denied");
        return Result.ok(configService.selectProvider(RequestContext.get().getUserId(),
                request.getProvider(), request.getExpectedGeneration()));
    }

    @GetMapping("/{provider}/group-chat")
    public Result<GroupChatConfigVO> groupChat(@PathVariable String provider) {
        systemAdminService.requireSystemAdmin(RequestContext.get().getUserId(), "配置共享群聊机器人", "access.admin.im_group_chat_denied");
        return Result.ok(withStatus(configService.groupChat(provider)));
    }

    @PutMapping("/{provider}/group-chat")
    public Result<GroupChatConfigVO> updateGroupChat(@PathVariable String provider,
                                                     @Valid @RequestBody UpdateGroupChatConfigRequest request) {
        systemAdminService.requireSystemAdmin(RequestContext.get().getUserId(), "配置共享群聊机器人", "access.admin.im_group_chat_denied");
        return Result.ok(withStatus(configService.updateGroupChat(
                RequestContext.get().getUserId(), provider, request)));
    }

    private GroupChatConfigVO withStatus(GroupChatConfigVO vo) {
        if (statusService == null) {
            vo.setStatus(ImChannelRuntimeStatusService.Status.DISABLED.name());
            return vo;
        }
        ImChannelRuntimeStatusService.RuntimeStatus status =
                statusService.status(vo.getProvider());
        vo.setStatus(status.status().name());
        vo.setDrainingCount(status.drainingCount());
        vo.setFailedCount(status.failedCount());
        vo.setUnknownCount(status.unknownCount());
        return vo;
    }
}
