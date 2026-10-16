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
}
