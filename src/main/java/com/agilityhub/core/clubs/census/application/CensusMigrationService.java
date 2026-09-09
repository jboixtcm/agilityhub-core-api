package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusMigrationRepository;
import com.agilityhub.core.platform.application.audit.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Internal import boundary; public signup validators intentionally do not apply to legacy census data. */
@Service
public class CensusMigrationService {
    private final CensusMigrationRepository repository;
    public CensusMigrationService(CensusMigrationRepository repository) { this.repository=repository; }
    public List<Map<String,Object>> snapshot(String entity) { return repository.snapshot(entity); }
    public void lock() { repository.lock(); }
    @Audited(action=AuditAction.MEMBER_UPDATED,entityType="'Member'",entity="#id",member="#id",reason="'MIGRATED'")
    public void member(String id, Map<String,Object> fields) { repository.write("members",id,fields); }
    @Audited(action=AuditAction.DOG_UPDATED,entityType="'Dog'",entity="#id",reason="'MIGRATED'")
    public void dog(String id, Map<String,Object> fields) { repository.write("dogs",id,fields); }
    @Audited(action=AuditAction.FAMILY_GROUP_CHANGED,entityType="'FamilyGroup'",entity="#id",reason="'MIGRATED'")
    public void group(String id, Map<String,Object> fields) { repository.write("family_groups",id,fields); }
}
