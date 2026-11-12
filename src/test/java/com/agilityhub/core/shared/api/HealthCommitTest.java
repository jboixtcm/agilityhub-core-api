package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.persistence.DatabaseProbe;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** REL-02: the health names the commit the running build was made from, so the live api commit can be read from the system. */
class HealthCommitTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static HealthController controller(String commit, boolean up) {
        var entries = new Properties();
        entries.setProperty("version", "0.1.0-SNAPSHOT");
        entries.setProperty("time", "2026-11-12T08:00:00Z");
        if (commit != null) { entries.setProperty("commit", commit); }
        var database = mock(DatabaseProbe.class);
        when(database.up()).thenReturn(up);
        return new HealthController(new BuildProperties(entries), database);
    }

    @Test void REL_02_theRecordedCommitIsReturnedWhetherTheDatabaseIsUpOrDown() {
        for (boolean up : new boolean[] {true, false}) {
            var body = controller(COMMIT, up).health(new MockHttpServletRequest()).getBody();
            assertThat(body).isNotNull();
            assertThat(body.status()).isEqualTo(up ? "UP" : "DOWN");
            assertThat(body.commit()).isEqualTo(COMMIT);
        }
    }

    @Test void REL_02_aBuildGivenNoCommitAnswersNull() {
        for (String commit : new String[] {HealthController.UNKNOWN_COMMIT, "", null}) {
            var body = controller(commit, true).health(new MockHttpServletRequest()).getBody();
            assertThat(body).isNotNull();
            assertThat(body.commit()).as(String.valueOf(commit)).isNull();
        }
    }
}
