package com.agilityhub.core.configuration;

import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3-T10 step 9: per S04 route, the catalog codes its handler can throw are the ones its OpenAPI error responses list
 * ({@link ContractErrors}). The throwable codes are read from the bytecode: every `ErrorCode` constant reachable from the
 * handler through the calls, lambdas and method references of the S04 slice (the census, signup and payment contexts and
 * the few identity/platform services they call), an interface call reaching each implementation. Codes that the generic
 * error responses already cover (authentication, tenancy, a server fault) are left out, and the cross-cutting ones come from
 * the route's own shape: a validated body, an Idempotency-Key, a rate limit, a module guard.
 */
class S04ErrorContractTest {
    private static final JavaClasses CLASSES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.agilityhub.core");
    private static final String BASE = "com.agilityhub.core.";
    /** The classes the traversal follows: S04's own contexts and the services of other contexts that its handlers call. */
    private static final List<String> SLICE = List.of(BASE + "clubs.census.", BASE + "clubs.signup.", BASE + "payments.",
            BASE + "identity.application.SignupIdentityService", BASE + "identity.application.CensusIdentityService",
            BASE + "platform.application.CountryContacts", BASE + "platform.application.CountryContactRules", BASE + "platform.application.CensusClubSettings",
            BASE + "platform.domain.", BASE + "clubs.followup.application.AttachmentService", BASE + "clubs.catalogs.application.SignupPlanData");
    /**
     * Answered by the generic responses of every operation (401/403/404/500): authentication, the tenant guards, S02 host
     * resolution and configuration faults (a stored parameter was validated when it was written, S02).
     */
    private static final Set<String> GENERIC = Set.of("UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND", "UNKNOWN_HOST", "TENANT_MISMATCH",
            "INTERNAL_ERROR", "UNKNOWN_PARAMETER", "PARAMETER_INVALID", "NOT_IMPLEMENTED", "IMPERSONATION_DENIED");
    /** Codes that the bytecode names but no S04 route can answer, each with the reason. */
    private static final Map<String, String> NEVER_ANSWERED = Map.of(
            "LEVELS_DISABLED", "DogService.assignLevel: validation assigns levels only with levels.enabled",
            "LEVEL_UNCHANGED", "DogService.assignLevel: never for the signup assignment");
    /** A signup upload has the fixed purpose SIGNUP_DOCUMENT: AttachmentService never finds it mismatched or module-gated. */
    private static final Map<String, String> SIGNUP_UPLOAD = Map.of("ATTACHMENT_ENTITY_MISMATCH", "fixed purpose SIGNUP_DOCUMENT",
            "MODULE_DISABLED", "SIGNUP_DOCUMENT is not a TASKS/ACTIVITIES purpose");
    /** Only an insert can meet another member's identity document; the D2 commands save a member with its own. */
    private static final Map<String, String> SAVED_MEMBER = Map.of("ID_DOCUMENT_ALREADY_EXISTS", "the member is saved with its unchanged document",
            "CHIP_ALREADY_EXISTS", "no dog is inserted and no chip changes");

    record Route(String name, Class<?> controller, String method, Set<String> crossCutting, Map<String, String> notAnswered) { }
    static final Class<?> SIGNUP = com.agilityhub.core.clubs.census.api.SignupController.class;
    static final List<Route> ROUTES = List.of(
            new Route("GET /signup", SIGNUP, "config", Set.of(), Map.of("PLAN_NOT_AVAILABLE", "only offered or the member's assignable plans are quoted",
                    "VALIDATION_ERROR", "the quotes use the catalog's own options")),
            new Route("POST /signup/identity-checks", SIGNUP, "identityCheck", Set.of("VALIDATION_ERROR", "RATE_LIMITED"),
                    Map.of("MEMBER_NOT_ACTIVE", "an inactive account is caught and answered CONTACT_CLUB")),
            new Route("GET /signup/towns", SIGNUP, "towns", Set.of("VALIDATION_ERROR", "RATE_LIMITED"), Map.of()),
            new Route("POST /signup/upload-urls", SIGNUP, "uploadUrl", Set.of("VALIDATION_ERROR", "RATE_LIMITED"), SIGNUP_UPLOAD),
            new Route("POST /signup/family-group-lookups", SIGNUP, "familyGroup", Set.of("VALIDATION_ERROR", "RATE_LIMITED", "MODULE_DISABLED"), Map.of()),
            new Route("POST /signup", SIGNUP, "signup", Set.of("VALIDATION_ERROR", "RATE_LIMITED", "IDEMPOTENCY_KEY_REUSED"), SIGNUP_UPLOAD),
            new Route("POST /me/dogs/signup", SIGNUP, "addDog", Set.of("VALIDATION_ERROR", "IDEMPOTENCY_KEY_REUSED"),
                    Map.of("ATTACHMENT_ENTITY_MISMATCH", SIGNUP_UPLOAD.get("ATTACHMENT_ENTITY_MISMATCH"), "MODULE_DISABLED", SIGNUP_UPLOAD.get("MODULE_DISABLED"),
                            "ID_DOCUMENT_ALREADY_EXISTS", SAVED_MEMBER.get("ID_DOCUMENT_ALREADY_EXISTS"))),
            new Route("GET /members/{id}/signup", SIGNUP, "review", Set.of(), Map.of("VALIDATION_ERROR", "the quote uses the options stored at submission")),
            new Route("POST /members/{id}/validation", SIGNUP, "validate", Set.of("VALIDATION_ERROR"), SAVED_MEMBER),
            new Route("POST /members/{id}/rejection", SIGNUP, "reject", Set.of("VALIDATION_ERROR"), SAVED_MEMBER),
            // Round 2, point 4 (S04 §6, D2): the census PATCH routes also edit a pending signup (`patchPending`).
            new Route("PATCH /members/{id}", com.agilityhub.core.clubs.census.api.MembersController.class, "updateMember", Set.of("VALIDATION_ERROR"),
                    Map.of("CHIP_ALREADY_EXISTS", "a member PATCH saves no dog")),
            new Route("PATCH /dogs/{id}", com.agilityhub.core.clubs.census.api.DogsController.class, "updateDog", Set.of("VALIDATION_ERROR"),
                    Map.of("ATTACHMENT_ENTITY_MISMATCH", SIGNUP_UPLOAD.get("ATTACHMENT_ENTITY_MISMATCH"), "MODULE_DISABLED", SIGNUP_UPLOAD.get("MODULE_DISABLED"),
                            "ID_DOCUMENT_ALREADY_EXISTS", "a dog PATCH saves no member")),
            new Route("POST /checkout-sessions", com.agilityhub.core.payments.api.CheckoutController.class, "create",
                    Set.of("VALIDATION_ERROR", "RATE_LIMITED", "IDEMPOTENCY_KEY_REUSED", "MODULE_DISABLED"), Map.of()));

    @Test void T_04_25_everyS04RouteDocumentsTheCodesItsHandlerCanThrow() throws Exception {
        var problems = new ArrayList<String>();
        for (var route : ROUTES) {
            var documented = documented(route);
            var reached = new TreeMap<String, String>(reachable(route));
            GENERIC.forEach(reached::remove); NEVER_ANSWERED.keySet().forEach(reached::remove); route.notAnswered().keySet().forEach(reached::remove);
            var expected = new TreeSet<>(reached.keySet()); expected.addAll(route.crossCutting());
            var missing = new TreeSet<>(expected); missing.removeAll(documented);
            // A generic code may still be named explicitly (the S04 table does, e.g. the checkout's 401 and 404).
            var extra = new TreeSet<>(documented); extra.removeAll(expected); extra.removeAll(GENERIC);
            for (String code : missing) { problems.add(route.name() + " throws " + code + " (via " + reached.getOrDefault(code, "the route") + ") but does not document it"); }
            for (String code : extra) { problems.add(route.name() + " documents " + code + " but its handler cannot throw it"); }
        }
        assertThat(problems).as(String.join("\n", problems)).isEmpty();
    }
    @Test void T_04_25_theTraversalFollowsLambdasInterfacesAndOtherContextsServices() {
        // The analysis itself: POST /signup reaches SignupService.submit through the transaction lambda, the IBAN check of the
        // country profile through CountryContacts, and the member lookup through CensusAccess.
        var signup = reachable(ROUTES.stream().filter(r -> r.name().equals("POST /signup")).findFirst().orElseThrow());
        assertThat(signup).containsKeys("SIGNUP_ALREADY_PENDING", "INVALID_IBAN", "MEMBER_ERASED", "DOG_CHIP_ALREADY_REGISTERED");
        var validation = reachable(ROUTES.stream().filter(r -> r.name().equals("POST /members/{id}/validation")).findFirst().orElseThrow());
        assertThat(validation).containsKeys("LEVEL_NOT_ACTIVE", "MEMBERSHIP_EXISTS", "UPFRONT_AMOUNT_EXCEEDS_DUE");
        // Round 2, point 4: the D2 PATCH routes reach the pending-signup edits (`patchPending`) through the transaction lambda.
        var member = ROUTES.stream().filter(r -> r.name().equals("PATCH /members/{id}")).findFirst().orElseThrow();
        assertThat(reachable(member)).containsKeys("INVALID_IBAN", "PAYMENT_METHOD_NOT_AVAILABLE");
        assertThat(documented(member)).contains("INVALID_IBAN", "PAYMENT_METHOD_NOT_AVAILABLE");
        var dog = ROUTES.stream().filter(r -> r.name().equals("PATCH /dogs/{id}")).findFirst().orElseThrow();
        assertThat(reachable(dog)).containsKeys("DOCUMENT_TYPE_UNKNOWN", "FILE_NOT_FOUND");
        assertThat(documented(dog)).contains("DOCUMENT_TYPE_UNKNOWN", "FILE_NOT_FOUND");
    }

    private static Set<String> documented(Route route) {
        Method method = Arrays.stream(route.controller().getDeclaredMethods()).filter(m -> m.getName().equals(route.method())).findFirst().orElseThrow();
        var errors = method.getAnnotation(ContractErrors.class);
        return errors == null ? Set.of() : Arrays.stream(errors.value()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }
    /** Code → the first code unit (a readable path hint) that names it, for every code reachable from the handler. */
    static Map<String, String> reachable(Route route) {
        var start = CLASSES.get(route.controller()).getMethods().stream().filter(m -> m.getName().equals(route.method())).findFirst().orElseThrow();
        var codes = new TreeMap<String, String>(); var seen = new HashSet<JavaCodeUnit>(); var queue = new ArrayDeque<JavaCodeUnit>(List.of(start)); seen.add(start);
        while (!queue.isEmpty()) {
            var unit = queue.pop();
            for (var access : unit.getFieldAccesses()) {
                if (access.getTargetOwner().isEquivalentTo(ErrorCode.class)) { codes.putIfAbsent(access.getTarget().getName(), unit.getFullName()); }
            }
            var targets = new ArrayList<AccessTarget.CodeUnitAccessTarget>();
            unit.getCallsFromSelf().forEach(call -> targets.add(call.getTarget()));
            unit.getMethodReferencesFromSelf().forEach(reference -> targets.add(reference.getTarget()));
            unit.getConstructorReferencesFromSelf().forEach(reference -> targets.add(reference.getTarget()));
            for (var target : targets) {
                if (!inSlice(target.getOwner())) { continue; }
                for (var next : implementations(target)) { if (seen.add(next)) { queue.add(next); } }
            }
        }
        return codes;
    }
    private static boolean inSlice(JavaClass owner) { return SLICE.stream().anyMatch(prefix -> owner.getName().startsWith(prefix)); }
    /** The member itself and, for an interface or an overridable method, the implementations in the slice. */
    private static List<JavaCodeUnit> implementations(AccessTarget.CodeUnitAccessTarget target) {
        var result = new ArrayList<JavaCodeUnit>();
        target.resolveMember().ifPresent(result::add);
        if (!(target instanceof AccessTarget.MethodCallTarget) && !(target instanceof AccessTarget.MethodReferenceTarget)) { return result; }
        var parameters = target.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
        for (var type : target.getOwner().getAllSubclasses()) {
            if (inSlice(type)) { type.tryGetMethod(target.getName(), parameters).ifPresent(result::add); }
        }
        return result;
    }
}
