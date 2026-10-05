package com.agilityhub.core.clubs.messaging.domain;

import com.agilityhub.core.platform.application.Module;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.messaging.domain.NotificationAudience.*;
import static com.agilityhub.core.clubs.messaging.domain.NotificationChannel.*;
import static org.assertj.core.api.Assertions.*;

/**
 * S11 WP-11-A (E7-T01): `NotificationCatalog` equals `docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md`, both tables (main
 * and Annex A), row by row and in both directions — code set, category, audiences, default channels per audience, variables
 * and action — and every event and variable it names comes from the catalogs. Pattern of ParameterCatalog's
 * `T-02-03_catalogMatchesDocument` and `ErrorCatalogContractTest`.
 */
class NotificationCatalogContractTest {
    static final Path CATALOG = Path.of("docs/specs/00-transversal/CATALEG_NOTIFICACIONS.md");
    static final Path EVENTS = Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md");
    static final Path S11 = Path.of("docs/specs/S11-comunicacions.md");
    static final Pattern CHANNEL = Pattern.compile("\\b(APP|EMAIL|SMS|PUSH)\\b"), AUDIENCE = Pattern.compile("\\b(MEMBER|APPLICANT|ADMINS|INSTRUCTORS)\\b");
    static final Pattern GROUP = Pattern.compile("\\(([^)]*)\\)"), TICKED = Pattern.compile("`([a-z][a-zA-Z_]*)`"),
            EVENT = Pattern.compile("`([A-Z][a-z][A-Za-z]+)(?:/([A-Z][a-z]+))?");
    /** Audiences the document writes in words (see the NotificationCatalog Javadoc). */
    static final Map<String, NotificationAudience> WORDS = Map.of("inscrits", MEMBER, "selecció", MEMBER, "compte", MEMBER, "compte convidat", APPLICANT,
            "admins de plataforma", ADMINS);
    /** «(+X …)»: allowed, and the code's seed default (N-24 push is a preference toggle; N-32a follows notifyEmail; N-42 as E5-T01 sends it). */
    static final Map<String, Boolean> OPTIONAL_DEFAULTS = Map.of("N-24:MEMBER:PUSH", true, "N-32a:MEMBER:EMAIL", false, "N-42:ADMINS:EMAIL", true);
    /** S11 §8 «Variables noves» without a code of their own: formatting forms (R-11-05, D9 «persona_cognoms»). */
    static final Set<String> GENERAL = Set.of("member_last_names", "dog_name_article");
    /** The S11 §5/§8 formatting variables the E7-T01 task lists, plus `dogs` and `late` (R-11-05 «dogs llista…», ICU argument `late`). */
    static final Set<String> FORMATTING = Set.of("review_time", "review_day", "week_start", "dogs_count", "changes", "masked_account", "calendar_links",
            "expires_minutes", "month", "cap", "email", "document_type", "task_excerpt", "setup_kind", "retry_link", "concept", "count", "oldest_days", "state",
            "decision", "fee", "expires_days", "dogs", "late");
    /** The «Variables disponibles» line: its variable list ends at «Sintaxi»; its last sentence names the general variables (E66). */
    static final String AVAILABLE = "## Variables disponibles (claus de codi; etiqueta en l'idioma de l'admin a D9)";
    static final Pattern GENERAL_SENTENCE = Pattern.compile("`([a-z_]+)` és una variable general");
    /** Events the row does not name: S11 §7 (N-15/N-23), E5-T03 (N-46), and S12 R-12-22 / E8-T04 step 8 (N-35).
     * The N-35 row alignment is proposed in roadmap/MESSAGES.md; the event and notification already belong to the catalogs. */
    static final Map<String, Set<String>> EXTRA_EVENTS = Map.of("N-15", Set.of("WaitlistNotified"), "N-23", Set.of("DogDocumentPending"),
            "N-46", Set.of("WaitlistConsolidated", "BookingCreated"), "N-35", Set.of("MemberCardInvalidated"));
    /** Names a row writes that are no emitting event: N-15's SeatReleased (it leads to WaitlistNotified), N-46's reverted offer, N-50's ExportJob. */
    static final Map<String, Set<String>> NOT_EMITTED = Map.of("N-15", Set.of("SeatReleased"), "N-46", Set.of("WaitlistNotified"), "N-50", Set.of("ExportJob"));

    record Row(String code, String events, String category, String channels, String variables, String action, boolean annex) { }

    static List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>(); boolean annex = false;
        for (String line : Files.readAllLines(CATALOG)) {
            if (line.startsWith("## Annex A")) { annex = true; }
            if (!line.startsWith("| N-")) { continue; }
            String[] cells = line.split("\\|", -1);
            assertThat(cells).as(line).hasSize(10);
            rows.add(new Row(cells[1].strip(), cells[3].strip(), cells[4].strip(), cells[5].strip(), cells[6].strip(), cells[7].strip(), annex));
        }
        return rows;
    }
    static String annexParagraph() throws Exception {
        return Files.readAllLines(CATALOG).stream().filter(line -> line.startsWith("Variants:")).findFirst().orElseThrow();
    }
    /** The per-code additions of the Annex's «Variables noves» sentence (the «Origen:» sentence after it only explains them). */
    static Map<String, List<String>> annexVariables() throws Exception {
        String text = annexParagraph();
        text = text.substring(text.indexOf("Variables noves:"));
        if (text.contains("Origen:")) { text = text.substring(0, text.indexOf("Origen:")); }
        var result = new LinkedHashMap<String, List<String>>(); var general = new TreeSet<String>();
        for (String segment : text.split("\\)")) {
            if (!segment.contains("(")) { continue; }
            var names = new ArrayList<String>(); Matcher ticked = TICKED.matcher(segment.substring(0, segment.indexOf('(')));
            while (ticked.find()) { names.add(ticked.group(1)); }
            for (String name : names) { if (GENERAL.contains(name)) { general.add(name); } }
            names.removeAll(GENERAL);
            for (String code : segment.substring(segment.indexOf('(') + 1).split("/")) { result.computeIfAbsent(code.strip(), key -> new ArrayList<>()).addAll(names); }
        }
        assertThat(general).as("the two general variables are in the paragraph").isEqualTo(GENERAL);
        return result;
    }
    /** The «Variables disponibles» line of the document. */
    static String availableLine() throws Exception {
        var lines = Files.readAllLines(CATALOG);
        return lines.get(lines.indexOf(AVAILABLE) + 2);
    }
    /** The variables the document declares general: any template may use them, whatever its code (`club_name`, E66). */
    static List<String> generalVariables() throws Exception {
        var names = new ArrayList<String>(); Matcher general = GENERAL_SENTENCE.matcher(availableLine().split("Sintaxi")[1]);
        while (general.find()) { names.add(general.group(1)); }
        return names;
    }
    static List<String> ticked(String text) {
        var names = new ArrayList<String>(); var matcher = TICKED.matcher(text);
        while (matcher.find()) { names.add(matcher.group(1)); }
        return names;
    }
    record Channels(Set<NotificationChannel> defaults, Set<NotificationChannel> optional, Set<NotificationChannel> off) { }

    @Test void WP_11_A_catalogMatchesTheDocumentRowByRowInBothDirections() throws Exception {
        var rows = rows();
        assertThat(rows.stream().filter(r -> !r.annex())).hasSize(43);
        assertThat(rows.stream().filter(Row::annex)).hasSize(18);
        assertThat(rows.stream().map(Row::code).toList()).as("both directions, the document's order").containsExactlyElementsOf(NotificationCatalog.codes())
                .doesNotContain("N-12");
        var additions = annexVariables();
        var optionalSeen = new TreeSet<String>(); var delegated = new TreeSet<String>(); var specialCategories = new TreeSet<String>();
        List<String> previous = List.of();
        for (Row row : rows) {
            var spec = NotificationCatalog.byCode(row.code()).orElseThrow();
            // Category: «SYSTEM+PERSONAL» (N-02: its e-mail is the magic link, its template is PERSONAL, S11 §8) and parentheses aside.
            String category = GROUP.matcher(row.category().replace("**", "")).replaceAll("").strip();
            if (category.contains("+")) { specialCategories.add(row.code() + ":" + category); category = category.substring(category.indexOf('+') + 1); }
            assertThat(spec.category()).as(row.code()).isEqualTo(NotificationCategory.valueOf(category));
            // Audiences and default channels.
            var expected = new LinkedHashMap<NotificationAudience, Channels>();
            for (String segment : row.channels().split(" · ")) {
                String audienceText = segment.substring(0, segment.indexOf('→')).strip(), channelText = segment.substring(segment.indexOf('→') + 1).replace("**", "").strip();
                var audiences = new ArrayList<NotificationAudience>(); Matcher words = AUDIENCE.matcher(GROUP.matcher(audienceText).replaceAll(""));
                while (words.find()) { audiences.add(NotificationAudience.valueOf(words.group(1))); }
                if (audiences.isEmpty()) { audiences.add(Objects.requireNonNull(WORDS.get(audienceText), row.code() + ": audience «" + audienceText + "»")); }
                if (channelText.matches("N-\\d+[a-z]? .*")) {
                    String target = channelText.substring(0, channelText.indexOf(' '));
                    delegated.add(row.code() + ":" + audiences + "→" + target);
                    assertThat(NotificationCatalog.byCode(target).orElseThrow().audiences()).containsAll(audiences);
                    continue;
                }
                var defaults = EnumSet.noneOf(NotificationChannel.class); var optional = EnumSet.noneOf(NotificationChannel.class); var off = EnumSet.noneOf(NotificationChannel.class);
                channels(GROUP.matcher(channelText).replaceAll(""), defaults);
                Matcher groups = GROUP.matcher(channelText);
                while (groups.find()) {
                    String group = groups.group(1).strip(); var named = EnumSet.noneOf(NotificationChannel.class); channels(group, named);
                    if (named.isEmpty()) { continue; }
                    if (group.startsWith("+")) { optional.addAll(named); } else if (group.contains(" off")) { off.addAll(named); }
                    else if (group.contains("segons")) { defaults.addAll(named); } else { fail(row.code() + ": unread parenthesis «" + group + "»"); }
                }
                for (var audience : audiences) { expected.put(audience, new Channels(defaults, optional, off)); }
            }
            assertThat(spec.audiences()).as(row.code() + " audiences").containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((audience, channels) -> {
                var seeded = EnumSet.noneOf(NotificationChannel.class); seeded.addAll(channels.defaults());
                for (var channel : channels.optional()) {
                    String key = row.code() + ":" + audience + ":" + channel; optionalSeen.add(key);
                    assertThat(spec.caps(audience).contains(channel) || channel == PUSH).as(key + " allowed").isTrue();
                    if (Objects.requireNonNull(OPTIONAL_DEFAULTS.get(key), key)) { seeded.add(channel); }
                }
                for (var channel : channels.off()) { assertThat(spec.caps(audience)).as(row.code() + " " + channel + " off but allowed").contains(channel); }
                assertThat(spec.defaultChannels(audience)).as(row.code() + " " + audience + " default channels").isEqualTo(seeded);
            });
            // Variables: the row (+ «idem», «free text»), then the Annex's per-code additions.
            List<String> variables = switch (row.variables()) {
                case "—" -> List.of();
                case "idem" -> previous;
                case "free text amb variables" -> NotificationCatalog.CUSTOM_VARIABLES;
                default -> Arrays.stream(row.variables().split(",")).map(v -> GROUP.matcher(v).replaceAll("")).map(v -> v.contains(":") ? v.substring(0, v.indexOf(':')) : v)
                        .map(v -> v.replace("**", "").strip()).toList();
            };
            previous = variables;
            var all = new ArrayList<>(variables); all.addAll(additions.getOrDefault(row.code(), List.of()));
            assertThat(spec.variables()).as(row.code() + " variables").containsExactlyElementsOf(all);
            // Action: «—», «admin: X» (the admins' copy only) or one action for every audience.
            String action = row.action().replace("**", "").strip();
            for (var audience : spec.audiences()) {
                NotificationActionType type = action.equals("—") ? null : action.startsWith("admin: ") ? audience == ADMINS ? NotificationActionType.valueOf(action.substring(7)) : null
                        : NotificationActionType.valueOf(action);
                assertThat(spec.action(audience)).as(row.code() + " " + audience + " action").isEqualTo(type);
            }
        }
        assertThat(optionalSeen).as("every «(+X …)» of the document has a decided default").isEqualTo(OPTIONAL_DEFAULTS.keySet());
        assertThat(delegated).containsExactly("N-17:[MEMBER]→N-08a");
        assertThat(specialCategories).containsExactly("N-02:SYSTEM+PERSONAL");
        System.out.println("NotificationCatalog parity: " + rows.size() + " rows (" + rows.stream().filter(r -> !r.annex()).count() + " main + "
                + rows.stream().filter(Row::annex).count() + " Annex A) = " + NotificationCatalog.codes().size() + " codes; N-12 absent");
    }
    private static void channels(String text, Set<NotificationChannel> into) {
        var matcher = CHANNEL.matcher(text);
        while (matcher.find()) { into.add(NotificationChannel.valueOf(matcher.group(1))); }
    }

    @Test void WP_11_A_theCodeRulesOfS11MatchTheCatalog() throws Exception {
        var specs = NotificationCatalog.specs();
        // R-11-12 caps: by category and audience; the only per-code exception is N-15 (SMS for the member).
        for (var spec : specs) {
            for (var audience : spec.audiences()) {
                var rule = NotificationCatalog.caps(spec.category(), audience);
                if (spec.code().equals("N-15") && audience == MEMBER) { rule.add(SMS); }
                assertThat(spec.caps(audience)).as(spec.code() + " " + audience).isEqualTo(rule);
            }
        }
        assertThat(NotificationCatalog.caps(NotificationCategory.CLUB_CHANGES, MEMBER)).containsExactlyInAnyOrder(APP, EMAIL, SMS);
        assertThat(NotificationCatalog.caps(NotificationCategory.CLUB_CHANGES, INSTRUCTORS)).containsExactlyInAnyOrder(APP, EMAIL);
        assertThat(NotificationCatalog.caps(NotificationCategory.CLUB_NEWS, MEMBER)).containsExactlyInAnyOrder(APP, EMAIL);
        assertThat(NotificationCatalog.caps(NotificationCategory.OPERATIONAL, APPLICANT)).containsExactly(EMAIL);
        assertThat(NotificationCatalog.caps(NotificationCategory.SYSTEM, MEMBER)).containsExactly(EMAIL);
        // Push, never stored in a template: N-13, N-15, N-24, N-33 (the «Públic → canals per defecte» column).
        assertThat(specs.stream().filter(s -> !s.push().isEmpty()).map(NotificationSpec::code)).containsExactly("N-13", "N-15", "N-24", "N-33");
        // S11 §3: mandatory codes; R-11-12: required variables.
        String s11 = Files.readString(S11);
        String mandatoryLine = s11.lines().filter(l -> l.contains("`mandatory` (del catàleg:")).findFirst().orElseThrow();
        var mandatory = new ArrayList<String>(); Matcher codes = Pattern.compile("N-\\d+[a-z]?").matcher(mandatoryLine.substring(mandatoryLine.indexOf("del catàleg:")));
        while (codes.find()) { mandatory.add(codes.group()); }
        assertThat(specs.stream().filter(NotificationSpec::mandatory).map(NotificationSpec::code)).containsExactlyInAnyOrderElementsOf(mandatory)
                .containsExactlyInAnyOrder("N-02", "N-08a", "N-15", "N-17", "N-32c", "N-36");
        assertThat(s11).contains("`requiredVariables`: N-02 `link`, N-08a `admin_text`");
        for (var spec : specs) {
            var required = spec.code().equals("N-02") ? Set.of("link") : spec.code().equals("N-08a") ? Set.of("admin_text") : Set.<String>of();
            assertThat(spec.requiredVariables()).as(spec.code()).isEqualTo(required);
        }
        // S11 §8 seed icons and colours; bell · NEUTRAL for the other codes.
        var seeds = new HashMap<String, String>();
        Matcher seed = Pattern.compile("^\\| (N-\\d+[a-z]?) \\| `([a-z]+)` · `([A-Z]+)` \\|", Pattern.MULTILINE).matcher(s11);
        while (seed.find()) { seeds.put(seed.group(1), seed.group(2) + ":" + seed.group(3)); }
        assertThat(seeds).hasSize(10);
        for (var spec : specs) { assertThat(spec.icon() + ":" + spec.color()).as(spec.code()).isEqualTo(seeds.getOrDefault(spec.code(), "bell:NEUTRAL")); }
        // R-11-09 dedup exceptions, R-11-16 relevance, R-11-17 module guards, R1 stage.
        for (var spec : specs) {
            assertThat(spec.dedupKeyFn()).as(spec.code()).isEqualTo(spec.code().equals("N-13") ? NotificationSpec.DedupKeyRule.PER_BOOKING
                    : spec.code().equals("N-24") ? NotificationSpec.DedupKeyRule.PER_BATCH_MEMBER : NotificationSpec.DedupKeyRule.DEFAULT);
            assertThat(spec.stillRelevantFn()).as(spec.code()).isEqualTo(spec.code().equals("N-13")
                    ? NotificationSpec.RelevanceRule.BOOKING_ACTIVE_AND_FUTURE : NotificationSpec.RelevanceRule.ALWAYS);
        }
        assertThat(s11).contains("`WAITLIST` off: N-15 mai; `BILLING` off: N-10/11/30/35/38 mai");
        assertThat(NotificationCatalog.byCode("N-15").orElseThrow().moduleGuards()).contains(Module.WAITLIST);
        for (String code : List.of("N-10", "N-11a", "N-11b", "N-30", "N-35", "N-38")) {
            assertThat(NotificationCatalog.byCode(code).orElseThrow().moduleGuards()).as(code).contains(Module.BILLING);
        }
        assertThat(specs.stream().filter(s -> s.stage() == NotificationSpec.Stage.LATER).map(NotificationSpec::code)).containsExactly("N-44", "N-45", "N-48");
        // A template's matrix never holds APPLICANT or PUSH; SYSTEM codes have none.
        for (var spec : specs) {
            spec.defaultMatrix().forEach((audience, row) -> { assertThat(audience).isNotEqualTo(APPLICANT); assertThat(row.keySet()).containsExactly(APP, EMAIL, SMS); });
            if (!spec.templated()) { assertThat(spec.defaultMatrix()).as(spec.code()).isEmpty(); }
        }
        assertThat(NotificationCatalog.byCode("N-08a").orElseThrow().defaultMatrix()).isEqualTo(Map.of(
                MEMBER, Map.of(APP, true, EMAIL, true, SMS, true), INSTRUCTORS, Map.of(APP, true, EMAIL, true, SMS, false), ADMINS, Map.of(APP, true, EMAIL, false, SMS, false)));
    }

    /**
     * S11 §8 seed texts (E7-T03's seed) use only the variables of their code: the row's, the Annex's additions, their derived
     * forms and the general ones (`club_name`). Round 1 pinned five codes here as a catalog proposal; ruling E66 added those
     * variables to the catalog and removed N-08a's `ring_name` from S11 §8, so nothing is missing any more.
     */
    @Test void WP_11_A_theS11SeedTextsUseOnlyTheirCodesVariables() throws Exception {
        var general = generalVariables();
        assertThat(general).containsExactly("club_name");
        assertThat(NotificationCatalog.GENERAL_VARIABLES).as("the catalog's general variables as code").containsExactlyElementsOf(general);
        var missing = new TreeMap<String, TreeSet<String>>();
        Matcher seed = Pattern.compile("^\\| (N-\\d+[a-z]?) \\| `[a-z]+` · `[A-Z]+` \\| (.*) \\| (.*) \\|$", Pattern.MULTILINE).matcher(Files.readString(S11));
        int rows = 0;
        while (seed.find()) {
            rows++;
            var used = new TreeSet<String>(); String text = seed.group(2) + " " + seed.group(3);
            Matcher variable = Pattern.compile("\\[\\[([a-z_]+)]]|\\{([a-z_]+), select").matcher(text);
            while (variable.find()) { used.add(variable.group(1) != null ? variable.group(1) : variable.group(2)); }
            var spec = NotificationCatalog.byCode(seed.group(1)).orElseThrow();
            used.removeAll(spec.variables()); used.removeAll(general);
            used.removeIf(name -> NotificationCatalog.DERIVED_VARIABLES.containsKey(name) && spec.variables().contains(NotificationCatalog.DERIVED_VARIABLES.get(name)));
            if (!used.isEmpty()) { missing.put(spec.code(), used); }
        }
        assertThat(rows).isEqualTo(10);
        assertThat(missing).as("seed texts using a variable their code lacks").isEmpty();
        // The Annex's additions of E66 are the codes' own now (the parity test above checks every row the same way).
        var additions = annexVariables();
        assertThat(additions.get("N-13")).containsExactly("kind", "class_description");
        assertThat(additions.get("N-28")).containsExactly("admin_text", "cancelled_count", "decision", "source", "member_first_name", "dog_name");
        for (String code : List.of("N-15", "N-16", "N-19")) { assertThat(additions.get(code)).as(code).contains("class_description"); }
        for (String code : List.of("N-21", "N-22")) { assertThat(additions.get(code)).as(code).containsExactly("gender"); }
        assertThat(additions.keySet()).allSatisfy(code -> assertThat(NotificationCatalog.byCode(code)).as(code).isPresent());
    }

    @Test void WP_11_A_everyEventAndVariableComesFromTheCatalogs() throws Exception {
        // Event names: the first column of every table row of CATALEG_ESDEVENIMENTS (main and Annex A).
        var events = new TreeSet<String>();
        for (String line : Files.readAllLines(EVENTS)) {
            if (!line.startsWith("|")) { continue; }
            Matcher name = Pattern.compile("`([A-Z][a-zA-Z]+)(?:[ `{])").matcher(line.split("\\|", -1)[1]);
            while (name.find()) { events.add(name.group(1)); }
        }
        var rows = rows();
        for (var spec : NotificationCatalog.specs()) {
            assertThat(events).as(spec.code() + " events").containsAll(spec.eventTypes());
            var named = new LinkedHashSet<String>(); Matcher event = EVENT.matcher(rows.stream().filter(r -> r.code().equals(spec.code())).findFirst().orElseThrow().events());
            while (event.find()) {
                named.add(event.group(1));
                // «`BookingCreated/Cancelled{…}`» is shorthand for BookingCreated and BookingCancelled (N-36, N-47).
                if (event.group(2) != null) { named.add(event.group(1).replaceAll("[A-Z][a-z]+$", "") + event.group(2)); }
            }
            named.removeAll(NOT_EMITTED.getOrDefault(spec.code(), Set.of())); named.addAll(EXTRA_EVENTS.getOrDefault(spec.code(), Set.of()));
            assertThat(spec.eventTypes()).as(spec.code() + " event types").containsExactlyInAnyOrderElementsOf(named);
        }
        assertThat(NotificationCatalog.specs().stream().filter(s -> s.eventTypes().isEmpty()).map(NotificationSpec::code)).containsExactly("N-50", "N-53");
        String s11 = Files.readString(S11);
        for (String extra : List.of("WaitlistNotified", "DogDocumentPending")) { assertThat(s11).contains("`" + extra); }
        assertThat(Files.readString(Path.of("docs/specs/S12-facturacio-i-pagaments.md")))
                .contains("`InvoiceFailed{STRIPE}` · `MemberCardInvalidated`");
        // Variables: «Variables disponibles», the Annex's «Variables noves», the 24-09 additions and the S11 formatting list.
        var lines = Files.readAllLines(CATALOG);
        var allowed = new TreeSet<String>();
        var available = ticked(availableLine().split("Sintaxi")[0]);
        // Round 1's proposal 4, accepted on 27-09 (E66): the fourteen variables the rows used are in the list now.
        assertThat(available).containsSubsequence("plan_name", "requested_date", "level", "actor", "period", "pending_count", "job_name", "error_count", "host",
                "challenge_title", "score", "role", "execute_date", "inviter_name");
        allowed.addAll(available);
        String paragraph = annexParagraph(); allowed.addAll(ticked(paragraph.substring(paragraph.indexOf("Variables noves:"))));
        allowed.addAll(ticked(lines.stream().filter(l -> l.startsWith("- **Variables afegides el 24-09")).findFirst().orElseThrow()));
        assertThat(allowed).contains("member_name", "from_month", "to_month", "upfront_total", "auto_cancel", "mode", "entityId", "audience", "change");
        allowed.addAll(FORMATTING);
        var used = new TreeSet<String>(); NotificationCatalog.specs().forEach(spec -> used.addAll(spec.variables()));
        var outside = new TreeSet<>(used); outside.removeAll(allowed);
        assertThat(outside).as("row variables no list of the catalogs declares").isEmpty();
        assertThat(allowed).containsAll(NotificationCatalog.CUSTOM_VARIABLES).containsAll(NotificationCatalog.DERIVED_VARIABLES.keySet())
                .containsAll(NotificationCatalog.DERIVED_VARIABLES.values()).containsAll(NotificationCatalog.GENERAL_VARIABLES);
        System.out.println("NotificationCatalog variables: " + used.size() + " used, " + outside.size() + " outside the catalog lists");
    }
}
