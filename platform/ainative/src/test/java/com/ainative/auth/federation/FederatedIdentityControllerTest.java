package com.ainative.auth.federation;

import com.ainative.auth.federation.dto.FederatedIdentityVO;
import com.ainative.auth.federation.dto.SsoBindStartRequest;
import com.ainative.auth.federation.dto.SsoTicketRequest;
import com.ainative.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederatedIdentityControllerTest {
    private static final Long USER = 10000L;

    private final FederatedIdentityService identityService = mock(FederatedIdentityService.class);
    private final FederationLoginService loginService = mock(FederationLoginService.class);
    private final FederatedIdentityController controller =
            new FederatedIdentityController(identityService, loginService);

    @AfterEach
    void cleanup() {
        RequestContext.destroy();
    }

    @Test
    void listReadsTheIdentitiesOfTheCurrentUser() {
        RequestContext.get().setUserId(USER);
        List<FederatedIdentityVO> identities = List.of(identity(5L));
        when(identityService.listMine(USER)).thenReturn(identities);

        assertSame(identities, controller.list().getData());
    }

    @Test
    void claimUsesTheTicketFromTheBody() {
        RequestContext.get().setUserId(USER);
        SsoTicketRequest request = new SsoTicketRequest();
        request.setTicket("ticket-1");
        FederatedIdentityVO claimed = identity(5L);
        when(identityService.claim(USER, "ticket-1")).thenReturn(claimed);

        assertSame(claimed, controller.claim(request).getData());
    }

    @Test
    void claimWithoutBodyForwardsANullTicket() {
        RequestContext.get().setUserId(USER);

        controller.claim(null);

        verify(identityService).claim(USER, null);
    }

    @Test
    void bindStartReturnsTheAuthorizeUrlForTheRequestedProvider() {
        RequestContext.get().setUserId(USER);
        SsoBindStartRequest request = new SsoBindStartRequest();
        request.setProviderId(7L);
        when(loginService.startBinding(USER, 7L)).thenReturn("https://idp.example.com/authorize");

        assertEquals("https://idp.example.com/authorize",
                controller.bindStart(request).getData().getAuthorizeUrl());
        verify(loginService).startBinding(USER, 7L);
    }

    @Test
    void bindStartWithoutBodyForwardsANullProvider() {
        RequestContext.get().setUserId(USER);

        controller.bindStart(null);

        verify(loginService).startBinding(USER, null);
    }

    @Test
    void unbindDeletesTheIdentityOfTheCurrentUser() {
        RequestContext.get().setUserId(USER);

        assertNull(controller.unbind(5L).getData());
        verify(identityService).unbind(USER, 5L);
    }

    private static FederatedIdentityVO identity(Long id) {
        FederatedIdentityVO vo = new FederatedIdentityVO();
        vo.setId(id);
        return vo;
    }
}
