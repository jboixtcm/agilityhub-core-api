package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.MigrationResetRepository;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs and audits the reset while the caller's resolved tenant scope is still open. */
@Service
public class MigrationResetChanges {
    private final MigrationResetRepository data;
    private final MigrationClubAccess clubs;
    private final ClubDefinitions definitions;

    public MigrationResetChanges(MigrationResetRepository data, MigrationClubAccess clubs, ClubDefinitions definitions) {
        this.data = data; this.clubs = clubs; this.definitions = definitions;
    }

    @Transactional
    @Audited(action = AuditAction.MIGRATION_APPLIED, entityType = "'Club'", entity = "#result", reason = "'RESET'")
    public String apply(String clubId, Path seed, Set<String> collections) {
        data.erase(collections); clubs.resetNumbers(); definitions.apply(seed, false);
        return clubId;
    }
}
