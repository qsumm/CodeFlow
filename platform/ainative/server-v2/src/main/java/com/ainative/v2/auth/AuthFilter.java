package com.ainative.v2.auth;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析 Bearer 令牌 → RequestContext。未带令牌不拦截(公开接口如登录/注册自行处理),
 * /api/auth/me 等需要身份的路由在控制器里 requireUserId。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        JwtService.TokenClaims claims = jwt.verifyClaims(token);
        if (claims != null && claims.userId() != null) {
            RequestContext.setUserId(claims.userId());
            RequestContext.setWorkspaceId(claims.workspaceId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** 供需要强制登录的接口调用:无身份时写 401 语义的包络并返回 null 表示已处理。 */
    public static boolean rejectIfAnonymous(HttpServletResponse response) throws IOException {
        if (RequestContext.userId() != null) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        new ObjectMapper().writeValue(response.getWriter(), ApiResult.fail("10401", "未登录或登录已失效"));
        return true;
    }
}
