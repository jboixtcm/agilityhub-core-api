package com.agilityhub.core.arch;

import com.agilityhub.core.identity.application.SupportAccessService;
import com.agilityhub.core.shared.application.RecentLogin;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-01 (S01 R-01-18, owner decision of 13 Nov 2026): the routes that need a recent sign-in of a staff caller are the personal-data
 * exports and their files, the remittance files, a member's bank account and the club's payment providers. The list below is the
 * inventory (a new one is a reviewed change), and every bearer route that S17 R-17-10 closes to support as an export or a SEPA or
 * migration file carries the annotation, so a new export cannot miss it. `PATCH /members/{id}` asks for it in its body when the
 * request carries `paymentMethod` (`MembersController.updateMember`, `StepUpIT`).
 */
class RecentLoginRoutesTest {
    static final List<String> EXPECTED = List.of(
            "DELETE /api/v1/platform/clubs/{id}/payment-providers/{provider}",
            "GET /api/v1/activities/export",
            "GET /api/v1/activity-registrations/export",
            "GET /api/v1/audit-entries/export",
            "GET /api/v1/billing/exports",
            "GET /api/v1/dogs/export",
            "GET /api/v1/exports",
            "GET /api/v1/exports/{id}",
            "GET /api/v1/exports/{id}/download",
            "GET /api/v1/instructor/week/export",
            "GET /api/v1/invoices/export",
            "GET /api/v1/members/export",
            "GET /api/v1/notifications/export",
            "GET /api/v1/platform/clubs/{clubId}/migrations/{runId}/report",
            "GET /api/v1/remittances/{id}/file",
            "GET /api/v1/remittances/{id}/spreadsheet",
            "GET /api/v1/training-bookings/export",
            "PATCH /api/v1/members/{id}/payment-method",
            "POST /api/v1/audit-entries/export",
            "POST /api/v1/dogs/export",
            "POST /api/v1/me/data-export",
            "POST /api/v1/members/export",
            "POST /api/v1/members/{id}/data-export",
            "PUT /api/v1/platform/clubs/{id}/payment-providers/{provider}");

    record Route(String line, boolean recentLogin) { }
    static List<Route> routes() {
        var routes = new ArrayList<Route>();
        var matcher = new AntPathMatcher();
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.agilityhub.core");
        for (var javaClass : classes) {
            if (!javaClass.isMetaAnnotatedWith(Controller.class) && !javaClass.isAnnotatedWith(Controller.class)) { continue; }
            Class<?> type = javaClass.reflect();
            var classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) { continue; }
                var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) { continue; }
                for (var verb : mapping.method()) {
                    for (String prefix : classMapping == null || classMapping.path().length == 0 ? List.of("") : List.of(classMapping.path())) {
                        for (String path : mapping.path().length == 0 ? List.of("") : Arrays.asList(mapping.path())) {
                            routes.add(new Route(verb.name() + " " + matcher.combine(prefix, path), method.isAnnotationPresent(RecentLogin.class)));
                        }
                    }
                }
            }
        }
        return routes;
    }

    @Test void AUTH_01_theRecentSignInRoutesAreTheExportsTheRemittanceFilesTheBankAccountAndThePaymentProviders() {
        assertThat(routes().stream().filter(Route::recentLogin).map(Route::line).sorted().toList()).containsExactlyElementsOf(EXPECTED);
    }

    @Test void AUTH_01_everyExportAndFileRouteClosedToSupportNeedsARecentSignIn() {
        var files = routes().stream().filter(route -> {
            String[] parts = route.line().split(" ", 2);
            String concrete = parts[1].replaceAll("\\{[^}]+}", "x");
            // The signed links (`/remittances/files/…`, `/migrations/files/…`) carry no bearer: issuing them is the guarded step.
            return SupportAccessService.restricted(parts[0], concrete) && !concrete.contains("/files/")
                    && (concrete.contains("export") || concrete.contains("/remittances/") || concrete.contains("/migrations/"));
        }).toList();
        assertThat(files).hasSizeGreaterThan(15).allSatisfy(route -> assertThat(route.recentLogin()).as(route.line()).isTrue());
    }
}
