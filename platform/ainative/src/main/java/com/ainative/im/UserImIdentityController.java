package com.ainative.im;

import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.im.dto.UpdateUserImIdentityRequest;
import com.ainative.im.dto.UserImIdentityVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/users/me/im-identities")
public class UserImIdentityController {
    private final UserImIdentityService identityService;

    public UserImIdentityController(UserImIdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping
    public Result<List<UserImIdentityVO>> list() {
        return Result.ok(identityService.list(RequestContext.get().getUserId()));
    }

    @PutMapping("/feishu")
    public Result<UserImIdentityVO> updateFeishu(@Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(RequestContext.get().getUserId(), "FEISHU", request.getExternalUserId()));
    }

    @PostMapping("/feishu/test")
    public Result<Void> testFeishu() {
        identityService.sendTest(RequestContext.get().getUserId(), "FEISHU");
        return Result.ok(null);
    }

    @PutMapping("/dingtalk")
    public Result<UserImIdentityVO> updateDingTalk(
            @Valid @RequestBody UpdateUserImIdentityRequest request) {
        return Result.ok(identityService.update(
                RequestContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey(),
                request.getExternalUserId()));
    }

    @PostMapping("/dingtalk/test")
    public Result<Void> testDingTalk() {
        identityService.sendTest(
                RequestContext.get().getUserId(),
                ImProviderType.DINGTALK.getKey());
        return Result.ok(null);
    }
}
