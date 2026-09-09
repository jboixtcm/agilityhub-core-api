package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("demo_seed_runs")
public record DemoSeedRun(@Id String id, String clubId, long seed, String specification, Map<String, Integer> counts) implements TenantEntity { }
