package com.agilityhub.core.platform.support;

import com.agilityhub.core.platform.persistence.Club;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class PlatformFixtures {
    private PlatformFixtures() { }
    public static Club club(String id, String host) { return club(id, host, "Europe/Madrid"); }
    public static Club club(String id, String host, String timeZone) {
        try (var input = PlatformFixtures.class.getResourceAsStream("/fixtures/platform/club.json")) {
            var mapper = new ObjectMapper().findAndRegisterModules();
            var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(input);
            tree.put("id", id); tree.put("slug", id); tree.put("timeZone", timeZone);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("domains").get(0)).put("host", host);
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree.get("domains").get(1)).put("host", "pending." + host);
            return mapper.treeToValue(tree, Club.class);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
