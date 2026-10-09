package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.mongodb.client.result.DeleteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivors of {@link SeatHoldRepository} (S08 R-08-21): a class cancellation reports how many holds it deleted. */
class SeatHoldRepositorySurvivorsTest {
    static final String CLUB = "club-a";

    final MongoTemplate mongo = mock(MongoTemplate.class);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_08_25_cancellingAClassDeletesItsHoldsAndCountsThem() {
        when(mongo.remove(any(Query.class), eq(SeatHold.class))).thenReturn(DeleteResult.acknowledged(2));

        assertThat(new SeatHoldRepository(mongo).deleteForClass("class-1")).isEqualTo(2L);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(query.capture(), eq(SeatHold.class));
        assertThat(query.getValue().getQueryObject()).containsEntry("clubId", CLUB).containsEntry("classSessionId", "class-1");
    }
}
