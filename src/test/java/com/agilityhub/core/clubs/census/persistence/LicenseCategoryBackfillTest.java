package com.agilityhub.core.clubs.census.persistence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;

class LicenseCategoryBackfillTest {
    @Test void T_03_32_cliDryRunsDoNotInitializeStoredCategories() {
        var mongo = mock(MongoTemplate.class);
        new LicenseCategoryBackfill(mongo).run(new DefaultApplicationArguments(
                "--core.command=migration:playoff", "--dry-run"));
        verifyNoInteractions(mongo);
    }
}
