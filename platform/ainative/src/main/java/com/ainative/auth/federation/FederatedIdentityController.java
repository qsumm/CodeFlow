package com.ainative.auth.federation;

import com.ainative.auth.federation.dto.FederatedIdentityVO;
import com.ainative.auth.federation.dto.SsoBindStartRequest;
import com.ainative.auth.federation.dto.SsoBindStartVO;
import com.ainative.auth.federation.dto.SsoTicketRequest;
import com.ainative.common.result.Result;
import com.ainative.context.RequestContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 个人设置页的企业身份管理。跨 IdP 绑定只允许从这里发起（需要已登录的 AW 会话），
 * 与登录入口共用同一套 IdP 校验。
 */
@RestController
@RequestMapping("/api/users/me/federated-identities")
public class FederatedIdentityController {

    private final FederatedIdentityService identityService;
    private final FederationLoginService loginService;

    public FederatedIdentityController(FederatedIdentityService identityService,
                                       FederationLoginService loginService) {
        this.identityService = identityService;
        this.loginService = loginService;
    }

    @GetMapping
    public Result<List<FederatedIdentityVO>> list() {
        return Result.ok(identityService.listMine(RequestContext.get().getUserId()));
    }

    @PostMapping("/claim")
    public Result<FederatedIdentityVO> claim(@RequestBody(required = false) SsoTicketRequest request) {
        Long userId = RequestContext.get().getUserId();
        return Result.ok(identityService.claim(userId, request == null ? null : request.getTicket()));
    }

    @PostMapping("/bind-start")
    public Result<SsoBindStartVO> bindStart(@RequestBody(required = false) SsoBindStartRequest request) {
        Long userId = RequestContext.get().getUserId();
        Long providerId = request == null ? null : request.getProviderId();
        return Result.ok(new SsoBindStartVO(loginService.startBinding(userId, providerId)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> unbind(@PathVariable("id") Long id) {
        identityService.unbind(RequestContext.get().getUserId(), id);
        return Result.ok(null);
    }
}
