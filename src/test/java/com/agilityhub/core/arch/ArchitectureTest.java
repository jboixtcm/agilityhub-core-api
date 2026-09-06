package com.agilityhub.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.agilityhub.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule E0_T01_domainHasNoWebDataSecurityOrServletDependencies =
            ArchitectureRules.DOMAIN_INDEPENDENCE;

    @ArchTest
    static final ArchRule E0_T01_contextsHaveNoCycles = ArchitectureRules.CONTEXT_CYCLES;

    @ArchTest
    static final ArchRule E0_T01_crossContextAccessUsesApplication = ArchitectureRules.CONTEXT_BOUNDARIES;

    @ArchTest
    static final ArchRule E0_T01_sharedDependsOnNoOtherContext = ArchitectureRules.SHARED_INDEPENDENCE;
}
