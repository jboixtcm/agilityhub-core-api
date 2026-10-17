package com.agilityhub.core.clubs.followup.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * INC-48 (E11-T02; review of E6-T03 round 5 #2): the keyed follow-up writes store the handler's own `@ResponseStatus`
 * ({@link com.agilityhub.core.shared.application.HandlerStatus}), never a second, hand-written copy of it.
 */
class KeyedStatusSourceTest {
    static final Pattern KEYED = Pattern.compile("transactions\\.keyed(?:WithoutBody)?\\(([^,]+),");

    @Test void INC_48_CONVENCIONS_API_7_everyKeyedFollowupWriteStoresTheHandlersOwnStatus() throws Exception {
        int calls = 0;
        for (String controller : List.of("TasksController", "AttachmentsController", "FollowupController")) {
            String source = Files.readString(Path.of("src/main/java/com/agilityhub/core/clubs/followup/api/" + controller + ".java"));
            assertThat(source).as(controller).doesNotContain("keyedNoContent(");
            var matcher = KEYED.matcher(source);
            while (matcher.find()) { calls++; assertThat(matcher.group(1).trim()).as(controller).isEqualTo("HandlerStatus.current()"); }
        }
        assertThat(calls).as("the keyed writes of TasksController, AttachmentsController and FollowupController").isEqualTo(10);
    }
}
