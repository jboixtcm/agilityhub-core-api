package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListEngine;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E5-T22 step 3 (review E5-T20 #1): D7's `waitlistRank` follows the row the page read, not the separate rank read. */
class ActivityApiServiceTest {
    final ListEngine lists = mock(ListEngine.class);
    final ActivityProjection projection = mock(ActivityProjection.class);
    final ActivityApiService service = new ActivityApiService(mock(ActivityService.class), mock(ActivityLifecycleService.class),
            mock(ActivityRegistrationService.class), projection, mock(ActivityQueryService.class), mock(ActivityNotifications.class),
            mock(SchedulingRecipients.class), lists);

    static Map<String, Object> row(String id, String state) { var row = new LinkedHashMap<String, Object>(); row.put("id", id); if (state != null) row.put("state", state); return row; }
    static MultiValueMap<String, String> params(String... pairs) {
        var params = new LinkedMultiValueMap<String, String>(); for (int i = 0; i < pairs.length; i += 2) params.add(pairs[i], pairs[i + 1]); return params;
    }
    void page(List<Map<String, Object>> rows) {
        when(lists.list(eq("activity-registrations"), any())).thenReturn(new ListPage<>(rows, 0, 50, rows.size(), 1,
                List.of(new Filter("activityId", FilterOperator.eq, "activity-a"))));
    }

    /**
     * The ranks come from a second read. Here they still rank r1 (promoted since: the page reads it ACTIVE) and r3 (cancelled):
     * those rows read `null`, and only the WAITLISTED row reads its rank.
     */
    @Test void R_07_08_aRankIsSentOnlyOnARowThePageReadAsWaitlisted() {
        page(List.of(row("r1", "ACTIVE"), row("r2", "WAITLISTED"), row("r3", "CANCELLED"), row("r4", "WAITLISTED")));
        when(projection.waitlistRanks("activity-a")).thenReturn(Map.of("r1", 1, "r2", 2, "r3", 3));
        var items = service.registrations("activity-a", params()).items();
        assertThat(items).extracting(item -> item.get("registrationId")).containsExactly("r1", "r2", "r3", "r4");
        assertThat(items).extracting(item -> item.get("waitlistRank")).containsExactly(null, 2, null, null);
        assertThat(items).allSatisfy(item -> assertThat(item).containsKey("waitlistRank"));
    }

    /** With `fields`, the page still reads `state` (the controller leaves it out again), so a requested rank is never lost. */
    @Test void R_07_08_withFieldsThePageStillReadsTheStateTheRankNeeds() {
        page(List.of(row("r1", "ACTIVE"), row("r2", "WAITLISTED")));
        when(projection.waitlistRanks("activity-a")).thenReturn(Map.of("r2", 1));
        @SuppressWarnings("unchecked") ArgumentCaptor<MultiValueMap<String, String>> sent = ArgumentCaptor.forClass(MultiValueMap.class);
        var items = service.registrations("activity-a", params("fields", "waitlistRank")).items();
        verify(lists).list(eq("activity-registrations"), sent.capture());
        assertThat(sent.getValue().get("fields")).containsExactly("waitlistRank,state");
        assertThat(sent.getValue().get("filter")).containsExactly("activityId:eq:activity-a");
        assertThat(items).extracting(item -> item.get("waitlistRank")).containsExactly(null, 1);
        // A request that already reads `state` goes unchanged (a repeated key would be INVALID_FILTER).
        reset(lists); page(List.of(row("r2", "WAITLISTED")));
        service.registrations("activity-a", params("fields", "state,position"));
        verify(lists).list(eq("activity-registrations"), sent.capture());
        assertThat(sent.getValue().get("fields")).containsExactly("state,position");
    }
}
