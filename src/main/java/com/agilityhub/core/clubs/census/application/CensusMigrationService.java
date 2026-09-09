package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusMigrationRepository;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal import boundary; public signup validators intentionally do not apply to legacy census data. */
@Service
public class CensusMigrationService {
    private final CensusMigrationRepository repository;
    public CensusMigrationService(CensusMigrationRepository repository) { this.repository=repository; }
    public List<Map<String,Object>> snapshot(String entity) { return repository.snapshot(entity); }
    public void lock() { repository.lock(); }
    public void member(String id, Map<String,Object> fields) { repository.write("members",id,fields); }
    public void dog(String id, Map<String,Object> fields) { repository.write("dogs",id,fields); }
    public void group(String id, Map<String,Object> fields) { repository.write("family_groups",id,fields); }
}
