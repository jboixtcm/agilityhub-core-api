package com.agilityhub.core.clubs.census.support;

import com.agilityhub.core.clubs.census.domain.DemoDataset;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/** Small and full fixtures consume the production generator and the same seed source. */
public final class DemoFixtures {
    private DemoFixtures() { }
    public static DemoDataset.Spec spec(ObjectMapper mapper, boolean small) throws Exception {
        try (var input = Files.newInputStream(Path.of("seeds/demo-canic.yaml"))) {
            Map<String, Object> values = new Yaml().load(input);
            if (small) {
                values.put("activeMembers", 8); values.put("inactiveMembers", 1); values.put("leftMembers", 1);
                values.put("familyGroups", 2); values.put("receivedDocuments", 2);
                values.put("accountEmails", ((List<?>) values.get("accountEmails")).subList(0, 8));
                var levels = new LinkedHashMap<String, Integer>();
                ((Map<?, ?>) values.get("levelDogs")).keySet().forEach(key -> levels.put(key.toString(), 2)); values.put("levelDogs", levels);
            }
            return mapper.convertValue(values, DemoDataset.Spec.class);
        }
    }
}
