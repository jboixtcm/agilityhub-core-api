package com.agilityhub.core.shared.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-48 (E11-T02; review of E6-T03 round 5 #2, CONVENCIONS_API §7): the status a keyed write stores with its key is read from
 * the handler's own `@ResponseStatus` (200 without one), so a repeated key can never answer another status than the first.
 */
class HandlerStatusTest {
    static class Routes {
        @ResponseStatus(HttpStatus.CREATED) public void created() { }
        @ResponseStatus(HttpStatus.NO_CONTENT) public void removed() { }
        public void read() { }
    }

    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    static void handling(String method) throws NoSuchMethodException {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, new HandlerMethod(new Routes(), Routes.class.getMethod(method)));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test void INC_48_CONVENCIONS_API_7_theStoredStatusIsTheHandlersOwn() throws Exception {
        handling("created"); assertThat(HandlerStatus.current()).isEqualTo(201);
        handling("removed"); assertThat(HandlerStatus.current()).isEqualTo(204);
        handling("read"); assertThat(HandlerStatus.current()).isEqualTo(200);
    }

    @Test void INC_48_outsideAHandlerThereIsNoStatusToStore() {
        assertThatIllegalStateException().isThrownBy(HandlerStatus::current);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThatIllegalStateException().isThrownBy(HandlerStatus::current);
    }
}
