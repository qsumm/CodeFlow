package com.ainative.auth.filter;

import com.ainative.auth.jwt.JwtService;
import com.ainative.auth.session.SessionService;
import com.ainative.workspace.WorkspaceDao;
import com.ainative.workspace.WorkspaceMemberDao;
import com.ainative.user.UserDao;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;
import com.ainative.common.i18n.SystemMessages;

@Configuration
public class AuthFilterConfig {

    @Bean(name = "localeResolver")
    public LocaleResolver localeResolver() {
        return new AcceptHeaderLocaleResolver() {
            @Override
            public java.util.Locale resolveLocale(javax.servlet.http.HttpServletRequest request) {
                return SystemMessages.resolveAcceptLanguage(request.getHeader("Accept-Language"));
            }
        };
    }

    @Bean
    public FilterRegistrationBean<AuthFilter> authFilterRegistration(
            JwtService jwtService, SessionService sessionService,
            WorkspaceMemberDao workspaceMemberDao, WorkspaceDao workspaceDao, UserDao userDao) {
        FilterRegistrationBean<AuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(new AuthFilter(jwtService, sessionService, workspaceMemberDao, workspaceDao, userDao));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(1);
        return reg;
    }
}
