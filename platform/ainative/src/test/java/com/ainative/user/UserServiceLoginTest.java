package com.ainative.user;

import com.ainative.access.SystemAdminService;
import com.ainative.auth.jwt.JwtProperties;
import com.ainative.auth.jwt.JwtService;
import com.ainative.auth.jwt.TokenPayload;
import com.ainative.auth.session.SessionIssuanceService;
import com.ainative.auth.session.SessionService;
import com.ainative.common.crypto.PasswordEncoderUtil;
import com.ainative.common.error.BizException;

import com.ainative.user.dto.LoginRequest;
import com.ainative.user.dto.LoginResponse;
import com.ainative.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceLoginTest {

    private UserDao userDao;
    private SessionService sessionService;
    private JwtService jwtService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        sessionService = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        service = new UserService(userDao, jwtService, sessionService, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwtService, sessionService, props));
    }

    @Test
    void login_returns_tokens_and_access_carries_userId() {
        UserDO u = new UserDO();
        u.setId(42L);
        u.setUsername("alice");
        u.setNickname("Alice Chen");
        u.setEmail("alice@example.com");
        u.setPasswordHash(PasswordEncoderUtil.encode("secret123"));
        u.setStatus(0);
        u.setIsAdmin(1);
        when(userDao.findByUsername("alice")).thenReturn(u);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("secret123");

        LoginResponse resp = service.login(req);

        assertEquals(42L, resp.getUserId());
        assertNotNull(resp.getUser());
        assertEquals(42L, resp.getUser().getId());
        assertEquals("alice", resp.getUser().getUsername());
        assertEquals("Alice Chen", resp.getUser().getNickname());
        assertEquals("alice@example.com", resp.getUser().getEmail());
        // The frontend renders the 平台配置 entry from this flag instead of re-deriving
        // "first user" semantics client-side.
        assertEquals(Boolean.TRUE, resp.getUser().getIsAdmin());
        assertNotNull(resp.getAccessToken());
        assertNotNull(resp.getRefreshToken());
        TokenPayload payload = jwtService.parse(resp.getAccessToken());
        assertEquals(42L, payload.getUserId());
        assertNull(payload.getCurrentWorkspaceId());
        verify(sessionService).storeRefresh(eq(resp.getRefreshToken()), eq(42L), anyInt());
    }

    @Test
    void login_wrong_password_throws_unauthorized() {
        UserDO u = new UserDO();
        u.setId(42L);
        u.setUsername("alice");
        u.setPasswordHash(PasswordEncoderUtil.encode("secret123"));
        u.setStatus(0);
        when(userDao.findByUsername("alice")).thenReturn(u);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("wrong");

        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals("10401", ex.getCode());
    }

    @Test
    void login_unknown_user_throws_unauthorized() {
        when(userDao.findByUsername("ghost")).thenReturn(null);
        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("x");
        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals("10401", ex.getCode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void wrongCredentialsKeepSpecificLogin401WithoutCreatingSession(String language) throws Exception {
        var request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("raw-password");
        BizException error = assertThrows(BizException.class, () -> service.login(request));
        assertEquals(BizException.class, error.getClass());
        assertEquals("10401", error.getCode());
        assertEquals("用户名或密码错误", error.getMessage());
        assertNull(error.getCause());
        verifyNoInteractions(sessionService);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AuthController(service))
                .setControllerAdvice(new com.ainative.common.web.GlobalExceptionHandler())
                .setLocaleResolver(new com.ainative.auth.filter.AuthFilterConfig().localeResolver()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .header("Accept-Language", language).contentType("application/json")
                        .content("{\"username\":\"ghost\",\"password\":\"raw-password\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("10401"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(
                        language.equals("en-US") ? "Incorrect username or password." : "用户名或密码错误"));
        verifyNoInteractions(sessionService);
    }

    @Test
    void rawSameLookingCredentialDiagnosticHasNoTranslationIntent() {
        var raw = new BizException(com.ainative.common.error.ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        assertNull(raw.getMessageKey());
        assertEquals("用户名或密码错误", com.ainative.common.i18n.SystemMessages.render(
                raw.getMessageKey(), raw.getMessageArgs(), raw.getMessage(), java.util.Locale.US));
    }

}
