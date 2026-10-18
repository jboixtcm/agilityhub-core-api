package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.ModuleGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RequiresModuleInterceptorTest {
    @Test
    void T_02_09_nonControllerHandlersDoNotRequireAModuleOrTenant() {
        var guard = mock(ModuleGuard.class);
        assertThat(new RequiresModuleInterceptor(guard).preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(guard);
    }

    /** A courses controller: `@RequiresModule(COURSES)` on its type, as `BuildSessionsController`. */
    @com.agilityhub.core.platform.application.RequiresModule(com.agilityhub.core.platform.application.Module.COURSES)
    static class Streaming { public void events() { } }

    /**
     * E9-T04 (S16 R-16-11): the dispatch that ends an SSE stream runs without the request's tenant; the module was checked when the
     * stream started, so it is not checked again (a failure there would come after the response was committed).
     */
    @Test
    void T_16_10_theAsyncDispatchThatEndsAStreamIsNotCheckedAgain() throws Exception {
        var guard = mock(ModuleGuard.class);
        var handler = new org.springframework.web.method.HandlerMethod(new Streaming(), Streaming.class.getMethod("events"));
        var ending = new MockHttpServletRequest();
        ending.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        assertThat(new RequiresModuleInterceptor(guard).preHandle(ending, new MockHttpServletResponse(), handler)).isTrue();
        verifyNoInteractions(guard);
        // The first dispatch still needs the tenant and the module.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RequiresModuleInterceptor(guard)
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
    }

    /** As `TrainingSlotsController.trainingSlots`: the staff keep their grid without FREE_TRAINING (INC-44). */
    static class StaffGrid {
        @com.agilityhub.core.platform.application.RequiresModule(value = com.agilityhub.core.platform.application.Module.FREE_TRAINING,
                exemptRoles = {"ADMIN", "INSTRUCTOR"})
        public void slots() { }
    }

    @Test
    void T_09_31_INC_44_anExemptRolePassesWithoutTheModuleAndEveryOtherCallerIsChecked() throws Exception {
        var guard = mock(ModuleGuard.class);
        org.mockito.Mockito.doThrow(new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.MODULE_DISABLED))
                .when(guard).require("club-a", com.agilityhub.core.platform.application.Module.FREE_TRAINING);
        var handler = new org.springframework.web.method.HandlerMethod(new StaffGrid(), StaffGrid.class.getMethod("slots"));
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        try (var tenant = com.agilityhub.core.shared.application.TenantContext.open("club-a")) {
            for (String role : java.util.List.of("ROLE_INSTRUCTOR", "ROLE_ADMIN")) {
                context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("staff", null, role));
                assertThat(new RequiresModuleInterceptor(guard).preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler)).isTrue();
            }
            verifyNoInteractions(guard);
            context.setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken("member", null, "ROLE_MEMBER"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RequiresModuleInterceptor(guard)
                    .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                    .isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
            context.setAuthentication(null);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RequiresModuleInterceptor(guard)
                    .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler))
                    .as("no authentication: checked").isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
