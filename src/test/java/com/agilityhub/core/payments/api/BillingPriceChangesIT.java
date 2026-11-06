package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.InvoicingService;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** S05 unbilled history corrections must preserve S12's simulation and issued-price guarantees. */
class BillingPriceChangesIT extends BillingItSupport {
    private static final String PRICE = "bill-price-abonat-monthly_fee";
    @MockitoSpyBean InvoicingService invoicing;

    @Test void T_12_09_deletingAnUnbilledPriceInvalidatesTheSimulation() throws Exception {
        String simulation = simulate("2026-09").path("id").asText();
        clock.setInstant(NOW.plusSeconds(60));
        ok(admin(delete("/api/v1/prices/" + PRICE)), 204);
        error(admin(keyed(post("/api/v1/billing/runs"), Map.of("period", "2026-09", "simulationId", simulation))), 409, "SIMULATION_STALE");
        assertThat(invoices()).isEmpty();
        assertThat(member("puig", "nextInvoiceDate")).isEqualTo("2026-09-01");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_12_10_aPriceCannotBeChangedWhileItsInvoiceIsBeingGenerated(boolean remove) throws Exception {
        String simulation = simulate("2026-09").path("id").asText();
        var planned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object plan = invocation.callRealMethod();
            planned.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) { throw new AssertionError("Invoice generation was not released"); }
            return plan;
        }).when(invoicing).plan(YearMonth.of(2026, 9));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var generated = pool.submit(() -> run("2026-09", simulation));
            assertThat(planned.await(30, TimeUnit.SECONDS)).isTrue();
            var changed = pool.submit(() -> call(admin(remove ? delete("/api/v1/prices/" + PRICE)
                    : patch("/api/v1/prices/" + PRICE).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("version", 0, "amount", Map.of("amountMinor", 6100, "currency", "EUR")))))));
            try {
                assertThatThrownBy(() -> changed.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            generated.get(30, TimeUnit.SECONDS);
            var response = changed.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
            assertThat(json(response).path("code").asText()).isEqualTo("PRICE_LOCKED");
            assertThat(invoices()).hasSize(8);
            var price = mongo.findById(PRICE, org.bson.Document.class, "prices");
            assertThat(price).isNotNull();
            assertThat(((Number) price.get("amount", org.bson.Document.class).get("amountMinor")).longValue()).isEqualTo(6000);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
