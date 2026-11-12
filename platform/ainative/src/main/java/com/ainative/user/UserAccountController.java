package com.ainative.user;

import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import com.ainative.user.dto.ChangePasswordRequest;
import com.ainative.user.dto.DeactivationRequest;
import com.ainative.user.dto.UpdateProfileRequest;
import com.ainative.user.dto.UserProfileVO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserAccountController {
    private final UserService userService;
    private final UserDeletionService userDeletionService;

    public UserAccountController(UserService userService,
                                 UserDeletionService userDeletionService) {
        this.userService = userService;
        this.userDeletionService = userDeletionService;
    }

    @GetMapping("/profile")
    public Result<UserProfileVO> getProfile() {
        return Result.ok(userService.getProfile(RequestContext.get().getUserId()));
    }

    @PutMapping("/profile")
    public Result<UserProfileVO> updateProfile(@RequestBody UpdateProfileRequest req) {
        return Result.ok(userService.updateProfile(RequestContext.get().getUserId(), req));
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        userService.changePassword(RequestContext.get().getUserId(), req);
        return Result.ok(null);
    }

    /**
     * 自助注销 = 立即逻辑删除（与平台用户管理的「删除用户」同一实现、同一校验、同一确认方式）；
     * 本人即操作人，故 adminInitiated = false。恢复由平台管理员在用户管理中执行。
     */
    @PostMapping("/deactivation")
    public Result<Void> initiateDeactivation(@RequestBody DeactivationRequest req) {
        Long userId = RequestContext.get().getUserId();
        userDeletionService.delete(userId, userId, req == null ? null : req.getConfirmUsername(), false);
        return Result.ok(null);
    }
}
