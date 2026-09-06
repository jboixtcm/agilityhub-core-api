package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantContextTest {
    @Test void T_02_06_contextIsImmutableAndRestoredAfterNestedScope() throws Exception {
        TenantContext.clear(); assertThat(TenantContext.current()).isNull();
        assertThatThrownBy(TenantContext::require).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TenantContext.open(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TenantContext.open(" ")).isInstanceOf(ApiException.class);
        try (var scope = TenantContext.open("club-a")) {
            try (var nested = TenantContext.open("club-a")) { assertThat(TenantContext.require()).isEqualTo("club-a"); }
            assertThat(TenantContext.require()).isEqualTo("club-a");
            assertThatThrownBy(() -> TenantContext.open("club-b")).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.TENANT_MISMATCH));
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(executor.submit(TenantContext::current).get()).isNull();
            }
        }
        assertThat(TenantContext.current()).isNull();
    }
}
