package com.agilityhub.core.configuration;

import com.agilityhub.core.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "shared.scheduling.enabled=false"})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class ManagementIT extends AbstractIntegrationTest {
    @LocalServerPort int apiPort;
    @LocalManagementPort int managementPort;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test void E0_T11_actuatorExistsOnlyOnPrivateListenerWithOnlyHealthInfoAndPrometheus() throws Exception {
        assertThat(apiPort).isNotEqualTo(managementPort);
        for (String endpoint : new String[]{"health", "info", "prometheus"}) {
            var management = get(managementPort, "/actuator/" + endpoint);
            assertThat(management.statusCode()).as(endpoint + " on management listener").isEqualTo(200);
            assertThat(management.headers().firstValue("Server")).isEmpty();
            assertThat(get(apiPort, "/actuator/" + endpoint).statusCode()).isEqualTo(401);
        }
        assertThat(get(managementPort, "/actuator/health").body()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(get(managementPort, "/actuator/prometheus").body()).contains("jvm_memory_used_bytes");
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/api/v1/health"}) {
            assertThat(get(managementPort, path).statusCode()).isEqualTo(403);
        }
        var spoofed = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + apiPort + "/actuator/health"))
                .header("X-Forwarded-Port", Integer.toString(managementPort)).header("Forwarded", "host=localhost:" + managementPort)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
