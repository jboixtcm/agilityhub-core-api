package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.persistence.MigrationResetRepository;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.definition.ClubDefinitions;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationResetService {
    private final MigrationResetRepository data; private final ClubDefinitions definitions; private final MigrationClubAccess clubs;
    private final ClubConfigService configs; private final TransactionTemplate transactions; private final Environment environment;
    public MigrationResetService(MigrationResetRepository data, ClubDefinitions definitions, MigrationClubAccess clubs,
            ClubConfigService configs, TransactionTemplate transactions, Environment environment) {
        this.data=data; this.definitions=definitions; this.clubs=clubs; this.configs=configs; this.transactions=transactions; this.environment=environment;
    }
    @Audited(action=AuditAction.MIGRATION_APPLIED, entityType="'Club'", entity="#result", reason="'RESET'")
    public String reset(String slug, Path seed, String firstConfirmation, String secondConfirmation) {
        if (environment.matchesProfiles("prod", "production") || !environment.matchesProfiles("local", "staging", "test")) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (!slug.equals(firstConfirmation) || !slug.equals(secondConfirmation)) { throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
        String clubId = clubs.resolve(slug);
        try (var tenant = TenantContext.open(clubId)) {
            if (!clubId.equals(definitions.apply(seed, true).id())) { throw new ApiException(ErrorCode.TENANT_MISMATCH); }
            var collections = data.collections();
            data.requireNoLaterWrites();
            transactions.executeWithoutResult(status -> {
                data.erase(collections); clubs.resetNumbers(); definitions.apply(seed, false);
            });
            configs.invalidate(clubId);
            return clubId;
        }
    }
}
