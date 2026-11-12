package com.agilityhub.core.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureRulesTest {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.data.mongodb.core.MongoTemplate",
        "org.springframework.security.core.Authentication",
        "jakarta.servlet.http.HttpServletRequest"
    })
    void E0_T01_domainRuleRejectsEachForbiddenFramework(String dependency) throws IOException {
        JavaClasses classes = compile(Map.of("identity.domain.InvalidDomain", dependency));

        assertThat(ArchitectureRules.DOMAIN_INDEPENDENCE.evaluate(classes).hasViolation()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "persistence", "domain"})
    void E0_T01_boundaryRuleRejectsOtherContextsInternals(String layer) throws IOException {
        JavaClasses classes = compile(Map.of(
                "courses.application.Client", ArchitectureRules.BASE_PACKAGE + "identity." + layer + ".Internal",
                "identity." + layer + ".Internal", "java.lang.String"));

        assertThat(ArchitectureRules.CONTEXT_BOUNDARIES.evaluate(classes).hasViolation()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application", "application.nested"})
    void E0_T01_boundaryRuleAllowsApplicationContracts(String layer) throws IOException {
        JavaClasses classes = compile(Map.of(
                "courses.application.Client", ArchitectureRules.BASE_PACKAGE + "identity." + layer + ".Contract",
                "identity." + layer + ".Contract", "java.lang.String"));

        ArchitectureRules.CONTEXT_BOUNDARIES.check(classes);
        ArchitectureRules.CONTEXT_CYCLES.check(classes);
    }

    @Test
    void E0_T01_boundaryRuleAllowsOwnContextInternals() throws IOException {
        JavaClasses classes = compile(Map.of(
                "identity.application.Service", ArchitectureRules.BASE_PACKAGE + "identity.persistence.Repository",
                "identity.persistence.Repository", "java.lang.String"));

        ArchitectureRules.CONTEXT_BOUNDARIES.check(classes);
    }

    @ParameterizedTest
    @CsvSource({"identity,courses", "clubs.census,clubs.bookings", "clubs.common,clubs.training"})
    void E0_T01_cycleRuleRejectsTopLevelAndClubContextCycles(String first, String second) throws IOException {
        JavaClasses classes = compile(Map.of(
                first + ".application.First", ArchitectureRules.BASE_PACKAGE + second + ".application.Second",
                second + ".application.Second", ArchitectureRules.BASE_PACKAGE + first + ".application.First"));

        assertThat(ArchitectureRules.CONTEXT_CYCLES.evaluate(classes).hasViolation()).isTrue();
        ArchitectureRules.CONTEXT_BOUNDARIES.check(classes);
    }

    @Test
    void E0_T01_sharedCannotDependEvenOnApplicationContracts() throws IOException {
        JavaClasses classes = compile(Map.of(
                "shared.application.Client", ArchitectureRules.BASE_PACKAGE + "platform.application.Contract",
                "platform.application.Contract", "java.lang.String"));

        assertThat(ArchitectureRules.SHARED_INDEPENDENCE.evaluate(classes).hasViolation()).isTrue();
        ArchitectureRules.CONTEXT_BOUNDARIES.check(classes);
    }

    @Test
    void E0_T01_sharedCanUseTheJdk() throws IOException {
        JavaClasses classes = compile(Map.of("shared.domain.Value", "java.math.BigDecimal"));

        ArchitectureRules.SHARED_INDEPENDENCE.check(classes);
        ArchitectureRules.DOMAIN_INDEPENDENCE.check(classes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"domain.Money", "persistence.TenantRepository", "persistence.GlobalRepository"})
    void T_02_06_boundaryAllowsSharedValueAndRepositoryContracts(String contract) throws IOException {
        JavaClasses classes = compile(Map.of(
                "platform.application.Client", ArchitectureRules.BASE_PACKAGE + "shared." + contract,
                "shared." + contract, "java.lang.String"));
        ArchitectureRules.CONTEXT_BOUNDARIES.check(classes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api.Internal", "persistence.OutboxRepository"})
    void T_02_06_boundaryStillRejectsSharedImplementationDetails(String internal) throws IOException {
        JavaClasses classes = compile(Map.of(
                "platform.application.Client", ArchitectureRules.BASE_PACKAGE + "shared." + internal,
                "shared." + internal, "java.lang.String"));
        assertThat(ArchitectureRules.CONTEXT_BOUNDARIES.evaluate(classes).hasViolation()).isTrue();
    }

    private static final String HANDLER = "@org.springframework.web.bind.annotation.GetMapping(\"/x\") public String x() { return \"\"; }";
    private static final String RULE = "@org.springframework.security.access.prepost.PreAuthorize(\"hasRole('ADMIN')\")";

    @Test
    void AUTHZ_03_aHandlerWithoutPreAuthorizeFailsAndOtherMethodsAreIgnored() throws IOException {
        JavaClasses classes = compileSources(Map.of(
                "clubs.census.api.OpenController", "@org.springframework.web.bind.annotation.RestController public class OpenController { "
                        + HANDLER + " public String helper() { return \"\"; } }",
                "clubs.census.api.PlainController", "@org.springframework.stereotype.Controller public class PlainController { "
                        + "@org.springframework.web.bind.annotation.RequestMapping(\"/y\") public String y() { return \"\"; } }",
                "clubs.census.application.NotAController", "public class NotAController { " + HANDLER + " }"));

        var details = ArchitectureRules.handlersDeclareTheirRoles(List.of()).evaluate(classes).getFailureReport().getDetails();

        assertThat(details).hasSize(2)
                .anyMatch(line -> line.contains("OpenController.x()")).anyMatch(line -> line.contains("PlainController.y()"))
                .noneMatch(line -> line.contains("helper") || line.contains("NotAController"));
    }

    @Test
    void AUTHZ_03_aRuleOnTheMethodOrOnTheClassIsEnough() throws IOException {
        JavaClasses classes = compileSources(Map.of(
                "clubs.census.api.MethodController", "@org.springframework.web.bind.annotation.RestController public class MethodController { "
                        + RULE + " " + HANDLER + " }",
                "clubs.census.api.ClassController", RULE + " @org.springframework.web.bind.annotation.RestController public class ClassController { "
                        + HANDLER + " }"));

        ArchitectureRules.handlersDeclareTheirRoles(List.of()).check(classes);
    }

    @Test
    void AUTHZ_03_aListedOpenRoutePassesAndAStaleEntryFails() throws IOException {
        JavaClasses classes = compileSources(Map.of(
                "clubs.census.api.OpenController", "@org.springframework.web.bind.annotation.RestController public class OpenController { " + HANDLER + " }",
                "clubs.census.api.GuardedController", "@org.springframework.web.bind.annotation.RestController public class GuardedController { "
                        + RULE + " " + HANDLER + " }"));

        ArchitectureRules.handlersDeclareTheirRoles(List.of("clubs.census.api.OpenController.x")).check(classes);
        var details = ArchitectureRules.handlersDeclareTheirRoles(List.of("clubs.census.api.OpenController.x",
                "clubs.census.api.OpenController.gone", "clubs.census.api.GuardedController.x")).evaluate(classes).getFailureReport().getDetails();
        assertThat(details).hasSize(2)
                .anyMatch(line -> line.contains("clubs.census.api.OpenController.gone is listed as an open route"))
                .anyMatch(line -> line.contains("clubs.census.api.GuardedController.x is listed as an open route"));
    }

    // AUTHZ-03: whole classes, compiled outside the test sources: a controller on the test classpath would join the application context.
    private JavaClasses compileSources(Map<String, String> sources) throws IOException {
        Path output = Files.createDirectory(temporaryDirectory.resolve("classes"));
        var arguments = new ArrayList<>(List.of(
                "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
        for (var entry : sources.entrySet()) {
            String name = ArchitectureRules.BASE_PACKAGE + entry.getKey();
            Path source = temporaryDirectory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, name.lastIndexOf('.')) + ";\n" + entry.getValue() + "\n");
            arguments.add(source.toString());
        }
        var compilerOutput = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(
                null, compilerOutput, compilerOutput, arguments.toArray(String[]::new));
        assertThat(result).withFailMessage(compilerOutput.toString(StandardCharsets.UTF_8)).isZero();
        return new ClassFileImporter().importPath(output);
    }

    // Compile small, deliberately invalid architectures outside src/main so the
    // production scan stays clean while these tests prove the rules can fail.
    private JavaClasses compile(Map<String, String> dependencies) throws IOException {
        Path output = Files.createDirectory(temporaryDirectory.resolve("classes"));
        var arguments = new ArrayList<>(List.of(
                "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()));
        for (var entry : dependencies.entrySet()) {
            String name = ArchitectureRules.BASE_PACKAGE + entry.getKey();
            int separator = name.lastIndexOf('.');
            Path source = temporaryDirectory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package " + name.substring(0, separator) + ";\npublic class "
                    + name.substring(separator + 1) + " { " + entry.getValue() + " dependency; }\n");
            arguments.add(source.toString());
        }
        var compilerOutput = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(
                null, compilerOutput, compilerOutput, arguments.toArray(String[]::new));
        assertThat(result).withFailMessage(compilerOutput.toString(StandardCharsets.UTF_8)).isZero();
        return new ClassFileImporter().importPath(output);
    }
}
