package com.ainative.user;

import com.ainative.common.result.Result;
import com.ainative.user.dto.LoginRequest;
import com.ainative.user.dto.LoginResponse;
import com.ainative.user.dto.LogoutRequest;
import com.ainative.user.dto.RefreshRequest;
import com.ainative.user.dto.RefreshResponse;
import com.ainative.user.dto.RegisterRequest;
import com.ainative.user.dto.UserVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<UserVO> register(@RequestBody RegisterRequest req) {
        return Result.ok(userService.register(req));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest req) {
        return Result.ok(userService.login(req));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody LogoutRequest req) {
        userService.logout(req);
        return Result.ok(null);
    }

    @PostMapping("/refresh")
    public Result<RefreshResponse> refresh(@RequestBody RefreshRequest req) {
        String accessToken = userService.refreshAccessToken(
                req.getRefreshToken(), req.getWorkspaceId());
        return Result.ok(new RefreshResponse(accessToken));
    }
}
