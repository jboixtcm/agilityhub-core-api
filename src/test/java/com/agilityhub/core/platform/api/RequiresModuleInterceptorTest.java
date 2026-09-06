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
}
