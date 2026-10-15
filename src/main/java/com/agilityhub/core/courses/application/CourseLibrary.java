package com.agilityhub.core.courses.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.courses.domain.*;
import com.agilityhub.core.courses.domain.CourseParts.ActiveRing;
import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16's course library (R-16-01…03, R-16-14, R-16-16, R-16-17). A club course lives in the tenant collection, a library or account
 * course is global (`GlobalCourseWriter`). Every model is validated against course-core's schema before it is stored with
 * `schemaVersion = 1` (R-16-01, E84), and the server reads its design size and statistics from it (`CourseModelFacts`); an edit is
 * in place, with the ten previous models in `history` (A8a). Deleting is soft and refused while an active setup shows the course.
 * Each write publishes its S16 §7 event in the same transaction.
 */
@Service
public class CourseLibrary {
    /** A course write: §6's body with §3/§14.2's additions. On a `PATCH` a null keeps the stored value. */
    public record CourseInput(Map<String, String> name, Discipline discipline, CourseSource source, JsonNode normalizedJson, Integer schemaVersion,
            String imageFileKey, String sourceFileKey, String thumbnailFileKey, String designerName, LocalDate designedOn, String eventName,
            AgilityHubLevel agilityhubLevel, List<String> levelIds, List<String> tags, String notes, Visibility visibility, SizeCategory sizeCategory,
            Double pathLengthM) { }
    /** A course as its reader sees it: the name in the request's language (R-16-17), signed URLs, the level label and its rings. */
    public record CourseView(Course course, String name, Map<String, String> nameI18n, String imageUrl, String thumbnailUrl, String level,
            List<ActiveRing> activeOnRings) { }
    public record CoursePage(List<CourseView> items, ListQuery query, long total) { }
    static final int HISTORY = 10;
    static final String SCOPE = "courses";

    private final CourseRepository courses;
    private final GlobalCourseRepository global;
    private final GlobalCourseWriter globalWriter;
    private final CourseAccess access;
    private final CourseFiles files;
    private final CourseSchemas schemas;
    private final PlacementRepository placements;
    private final RingSetupRepository setups;
    private final PlanningCatalogAccess catalogs;
    private final LevelLabels labels;
    private final ClubConfigService configs;
    private final IcuMessageSource messages;
    private final EventPublisher events;
    private final Clock clock;
    public CourseLibrary(CourseRepository courses, GlobalCourseRepository global, GlobalCourseWriter globalWriter, CourseAccess access,
            CourseFiles files, CourseSchemas schemas, PlacementRepository placements, RingSetupRepository setups, PlanningCatalogAccess catalogs,
            LevelLabels labels, ClubConfigService configs, IcuMessageSource messages, EventPublisher events, Clock clock) {
        this.courses = courses; this.global = global; this.globalWriter = globalWriter; this.access = access; this.files = files;
        this.schemas = schemas; this.placements = placements; this.setups = setups; this.catalogs = catalogs; this.labels = labels; this.configs = configs;
        this.messages = messages; this.events = events; this.clock = clock;
    }

    // ---- reads ----

    public CourseView get(String id) { var course = access.course(id, false); return view(course, activeRings(List.of(course.id())), levels()); }

    /** `GET /courses` (R-16-03): the club's courses, the AgilityHub library and the caller's own account courses that they may read. */
    public CoursePage list(MultiValueMap<String, String> params) {
        var query = ListQuery.parse(CourseListContract.COURSES, params);
        var visible = new ArrayList<Course>(courses.library());
        visible.addAll(global.library(CourseAccess.accountId()));
        visible.removeIf(course -> !CourseAccess.readable(course));
        return page(query, visible, activeRings(visible.stream().map(Course::id).toList()), levels());
    }
    /** `GET /platform/courses` (R-16-14): the AgilityHub library, with the same list contract. */
    public CoursePage listLibrary(MultiValueMap<String, String> params) {
        return page(ListQuery.parse(CourseListContract.COURSES, params), global.agilityHub(), Map.of(), Map.of());
    }
    private CoursePage page(ListQuery query, List<Course> visible, Map<String, List<ActiveRing>> rings, Map<String, String> levelCodes) {
        Map<String, ListDefinition.Type> types = new HashMap<>();
        CourseListContract.COURSES.filters().forEach((key, field) -> types.put(key, field.type()));
        var rows = visible.stream().map(course -> {
            Map<String, Object> values = new HashMap<>();
            values.put("owner", owner(course)); values.put("discipline", course.discipline().name());
            values.put("agilityhubLevel", course.agilityhubLevel() == null ? null : course.agilityhubLevel().name());
            values.put("levelId", course.levelIds()); values.put("source", course.source().name()); values.put("tag", course.tags());
            values.put("designerName", course.designerName()); values.put("obstacleCount", course.stats().obstacleCount());
            values.put("updatedAt", course.updatedAt().toString());
            var search = new ArrayList<>(course.name().values().values()); search.add(course.designerName());
            return new CourseListing.Row<>(course, course.id(), name(course), course.updatedAt(), search, values);
        }).toList();
        var page = CourseListing.page(query, types, rows);
        // Only the page's rows are signed and labelled.
        return new CoursePage(page.items().stream().map(course -> view(course, rings, levelCodes)).toList(), query, page.total());
    }
    /** §6's `owner` filter: the club's, the AgilityHub library, or `MINE` (the caller's account courses). */
    private static String owner(Course course) {
        return course.ownerType() == OwnerType.ACCOUNT ? CourseAccess.author(course, CourseAccess.accountId()) ? "MINE" : "ACCOUNT" : course.ownerType().name();
    }

    /** R-16-17: the name in the request's language, else the reader's club default (the course's own default outside a club). */
    private String name(Course course) {
        String fallback = TenantContext.current() == null ? course.name().defaultLocale() : configs.get(TenantContext.require()).club().defaultLocale();
        return course.name().withDefaultLocale(fallback).resolve(LocaleContext.current()).value();
    }
    private CourseView view(Course course, Map<String, List<ActiveRing>> rings, Map<String, String> levelCodes) {
        String name = name(course);
        boolean editable = TenantContext.current() == null ? course.ownerType() == OwnerType.AGILITYHUB : CourseAccess.writable(course);
        return new CourseView(course, name, editable ? course.name().values() : null, files.url(course.imageFileKey()), files.url(course.thumbnailFileKey()),
                LevelLabels.label(levelCodes, course.levelIds(), course.agilityhubLevel()), rings.getOrDefault(course.id(), List.of()));
    }
    /** The club's level codes in catalog order (the list's «C–D» label), or none outside a club. */
    private Map<String, String> levels() { return labels.codes(); }
    /** The rings whose active setup shows each course, directly or through one of its placements (§6 `activeOnRings`). */
    private Map<String, List<ActiveRing>> activeRings(Collection<String> courseIds) {
        if (TenantContext.current() == null || courseIds.isEmpty()) { return Map.of(); }
        var active = setups.active();
        var viaPlacement = placements.courses(active.stream().map(RingSetup::placementId).filter(Objects::nonNull).toList());
        var byRing = new HashMap<String, Set<String>>();
        for (var setup : active) {
            var shown = new HashSet<String>();
            if (setup.courseId() != null) { shown.add(setup.courseId()); }
            if (setup.placementId() != null && viaPlacement.containsKey(setup.placementId())) { shown.add(viaPlacement.get(setup.placementId())); }
            shown.retainAll(courseIds);
            if (!shown.isEmpty()) { byRing.put(setup.ringId(), shown); }
        }
        var result = new HashMap<String, List<ActiveRing>>();
        if (byRing.isEmpty()) { return result; }
        for (var ring : catalogs.rings()) {
            for (String course : byRing.getOrDefault(ring.id(), Set.of())) {
                result.computeIfAbsent(course, key -> new ArrayList<>()).add(new ActiveRing(ring.id(), ring.name()));
            }
        }
        return result;
    }

    // ---- club writes ----

    @Transactional
    public CourseView create(CourseInput input) {
        var draft = Draft.create(UUID.randomUUID().toString(), OwnerType.CLUB, TenantContext.require(), null, clock.instant(), CourseAccess.accountId());
        apply(draft, input, null);
        draft.claimFiles();
        var created = draft.build();
        courses.insert(created);
        publishCreated(created, draft.facts);
        return view(created, Map.of(), levels());
    }
    @Transactional
    public CourseView patch(String id, CourseInput input, long version) { return patch(access.course(id, true), input, version); }
    @Transactional
    public void delete(String id, long version) {
        var before = access.course(id, true);
        if (before.version() != version) { throw new ApiException(STALE_VERSION); }
        if (!activeRings(List.of(before.id())).isEmpty()) { throw new ApiException(COURSE_IN_USE); }
        var draft = Draft.of(before); draft.deletedAt = clock.instant(); touch(draft, before);
        write(draft.build(), version);
        publish(EventKind.CourseDeleted, before, Map.of("courseId", before.id()));
    }
    /** «Duplica»: a copy with the same owner, which the caller must be able to write (the library is copied with `copy-to-club`). */
    @Transactional
    public CourseView duplicate(String id, Map<String, String> name) {
        var source = access.course(id, true);
        var draft = copy(source, name, source.ownerType(), source.clubId(), source.ownerAccountId());
        var copy = draft.build();
        insert(copy);
        publish(EventKind.CourseCopied, copy, Map.of("courseId", copy.id(), "fromCourseId", source.id()));
        return view(copy, Map.of(), levels());
    }
    /**
     * R-16-03 `copy-to-club`: a `PUBLIC` course becomes the club's own editable `CLUB` course, `source = AGILITYHUB_COPY` with
     * `sourceCourseId`; later changes of the original never reach the copy. Club levels and files of another club are not copied.
     */
    @Transactional
    public CourseView copyToClub(String id, Map<String, String> name) {
        var source = access.course(id, false);
        if (source.visibility() != Visibility.PUBLIC) { throw invalid("id", "VALIDATION_ERROR"); }
        String clubId = TenantContext.require();
        var draft = copy(source, name, OwnerType.CLUB, clubId, null);
        draft.visibility = Visibility.CLUB; draft.source = CourseSource.AGILITYHUB_COPY; draft.sourceCourseId = source.id(); draft.levelIds = List.of();
        String own = clubId + "/" + SCOPE + "/";
        if (draft.sourceFileKey != null && !draft.sourceFileKey.startsWith(own)) { draft.sourceFileKey = null; }
        if (draft.imageFileKey != null && !draft.imageFileKey.startsWith(own)) { draft.imageFileKey = null; }
        if (draft.thumbnailFileKey != null && !draft.thumbnailFileKey.startsWith(own)) { draft.thumbnailFileKey = null; }
        var copy = draft.build();
        courses.insert(copy);
        publish(EventKind.CourseCopied, copy, Map.of("courseId", copy.id(), "fromCourseId", source.id()));
        return view(copy, Map.of(), levels());
    }

    // ---- AgilityHub library (R-16-14) ----

    @Transactional
    public CourseView createLibrary(CourseInput input) {
        var draft = Draft.create(UUID.randomUUID().toString(), OwnerType.AGILITYHUB, null, null, clock.instant(), CourseAccess.accountId());
        apply(draft, input, null);
        draft.claimFiles();
        var created = draft.build();
        globalWriter.insert(created);
        publishCreated(created, draft.facts);
        return view(created, Map.of(), Map.of());
    }
    @Transactional
    public CourseView patchLibrary(String id, CourseInput input, long version) { return patch(library(id), input, version); }
    private Course library(String id) {
        return global.findById(id).filter(course -> course.ownerType() == OwnerType.AGILITYHUB).orElseThrow(() -> new ApiException(NOT_FOUND));
    }

    // ---- write helpers ----

    private CourseView patch(Course before, CourseInput input, long version) {
        var draft = Draft.of(before);
        apply(draft, input, before);
        // E115 (E9-T02 review #6): new file keys are claimed only once the write is known to apply, its version included.
        if (before.version() != version) { throw new ApiException(STALE_VERSION); }
        draft.claimFiles();
        var diff = diff(before, draft);
        if (diff.isEmpty()) { return view(before, activeRings(List.of(before.id())), levels()); }
        if (draft.modelChanged && before.normalizedJson() != null && !before.normalizedJson().isNull()) {
            var history = new ArrayList<>(before.history() == null ? List.of() : before.history());
            history.add(new CourseHistory(before.version(), before.schemaVersion(), before.normalizedJson()));
            draft.history = List.copyOf(history.subList(Math.max(0, history.size() - HISTORY), history.size()));
        }
        touch(draft, before);
        var after = draft.build();
        write(after, version);
        publish(EventKind.CourseUpdated, after, Map.of("courseId", after.id(), "diff", diff));
        return view(after, activeRings(List.of(after.id())), levels());
    }
    private void touch(Draft draft, Course before) {
        draft.version = before.version() + 1; draft.updatedAt = clock.instant(); draft.updatedByAccountId = CourseAccess.accountId();
    }
    /** E115: a placement or setup that references the course meanwhile conflicts with the write (`409 STALE_VERSION`). */
    private void write(Course next, long expectedVersion) {
        WriteConflicts.guard(() -> { if (next.ownerType() == OwnerType.CLUB) { courses.update(next, expectedVersion); } else { globalWriter.update(next, expectedVersion); } });
    }
    private void insert(Course course) { if (course.ownerType() == OwnerType.CLUB) { courses.insert(course); } else { globalWriter.insert(course); } }
    private Draft copy(Course source, Map<String, String> name, OwnerType owner, String clubId, String ownerAccountId) {
        var draft = Draft.of(source);
        var now = clock.instant(); String me = CourseAccess.accountId();
        draft.id = UUID.randomUUID().toString(); draft.ownerType = owner; draft.clubId = clubId; draft.ownerAccountId = ownerAccountId;
        draft.version = 0; draft.createdAt = now; draft.createdByAccountId = me; draft.updatedAt = now; draft.updatedByAccountId = me;
        draft.history = List.of(); draft.deletedAt = null;
        draft.name = name == null ? source.name().withDefaultLocale(defaultLocale(source.name().values())) : localized(name, owner);
        return draft;
    }

    /** Applies a write to the draft and checks the result against S16 §3 and R-16-01/03/14. */
    private void apply(Draft draft, CourseInput in, Course before) {
        boolean club = draft.ownerType == OwnerType.CLUB || TenantContext.current() != null && draft.ownerType == OwnerType.ACCOUNT;
        if (in.name() != null) { draft.name = localized(in.name(), draft.ownerType); }
        if (in.discipline() != null) { draft.discipline = in.discipline(); }
        if (in.source() != null) {
            if (in.source() == CourseSource.AGILITYHUB_COPY && draft.source != CourseSource.AGILITYHUB_COPY) { throw invalid("source", "VALIDATION_ERROR"); }
            draft.source = in.source();
        }
        if (in.normalizedJson() != null && !in.normalizedJson().isNull() || in.schemaVersion() != null) {
            schemas.validateCourseData(in.schemaVersion(), in.normalizedJson());
            draft.modelChanged = before == null || !in.normalizedJson().equals(before.normalizedJson());
            draft.normalizedJson = in.normalizedJson(); draft.schemaVersion = 1;
        }
        // R-16-01 (E115): the path length is course-core's summary of the model it comes with; a new model without one reports none.
        if (in.pathLengthM() != null) { draft.pathLengthM = in.pathLengthM(); } else if (draft.modelChanged) { draft.pathLengthM = null; }
        if (in.designerName() != null) { draft.designerName = in.designerName(); }
        if (in.designedOn() != null) { draft.designedOn = in.designedOn(); }
        if (in.eventName() != null) { draft.eventName = in.eventName(); }
        if (in.agilityhubLevel() != null) { draft.agilityhubLevel = in.agilityhubLevel(); }
        if (in.notes() != null) { draft.notes = in.notes(); }
        if (in.sizeCategory() != null) { draft.sizeCategory = in.sizeCategory(); }
        if (in.visibility() != null) { draft.visibility = in.visibility(); }
        if (in.tags() != null) {
            if (in.tags().stream().anyMatch(tag -> tag == null || tag.isBlank())) { throw invalid("tags", "VALIDATION_ERROR"); }
            draft.tags = in.tags().stream().map(String::strip).distinct().toList();
        }
        if (in.levelIds() != null) { draft.levelIds = levelIds(in.levelIds(), club); }
        draft.sourceFileKey = file(draft, before == null ? null : before.sourceFileKey(), in.sourceFileKey(), UploadPurpose.SMARTER_SOURCE, club);
        draft.imageFileKey = file(draft, before == null ? null : before.imageFileKey(), in.imageFileKey(), UploadPurpose.COURSE_IMAGE, club);
        draft.thumbnailFileKey = file(draft, before == null ? null : before.thumbnailFileKey(), in.thumbnailFileKey(), UploadPurpose.THUMBNAIL, club);
        visibility(draft.ownerType, draft.visibility);
        if ((draft.source == CourseSource.SMARTER || draft.source == CourseSource.EDITOR) && (draft.normalizedJson == null || draft.normalizedJson.isNull())) {
            throw new ApiException(COURSE_MODEL_INVALID, Map.of("schemaErrors", List.of(Map.of("path", "$", "message", "normalizedJson is required unless the source is IMAGE"))));
        }
        if (draft.source == CourseSource.IMAGE && draft.imageFileKey == null) { throw invalid("imageFileKey", "REQUIRED"); }
        if (draft.ownerType == OwnerType.AGILITYHUB && draft.agilityhubLevel == null) { throw invalid("agilityhubLevel", "REQUIRED"); }
        if (in.pathLengthM() != null && (draft.normalizedJson == null || draft.normalizedJson.isNull())) { throw invalid("pathLengthM", "VALIDATION_ERROR"); }
        draft.facts = CourseModelFacts.of(draft.normalizedJson, draft.pathLengthM);
    }
    /** S16 §3: `PUBLIC` only for the library and account courses; the library is always `PUBLIC`; a club course is `CLUB` or `PRIVATE`. */
    private static void visibility(OwnerType owner, Visibility visibility) {
        boolean allowed = switch (owner) {
            case CLUB -> visibility != Visibility.PUBLIC;
            case AGILITYHUB -> visibility == Visibility.PUBLIC;
            case ACCOUNT -> visibility != Visibility.CLUB;
        };
        if (!allowed) { throw invalid("visibility", "VALIDATION_ERROR"); }
    }
    /**
     * None while `levels.enabled = false` (R-16-15: `422 LEVELS_DISABLED`, CATALEG_ERRORS §1, as every other context throws it;
     * E115), else club levels of the caller's club only (`404` otherwise). A tenantless library course names none.
     */
    private List<String> levelIds(List<String> ids, boolean club) {
        if (ids.isEmpty()) { return List.of(); }
        if (!club) { throw new ApiException(NOT_FOUND); }
        if (!access.enabled("levels.enabled")) { throw new ApiException(LEVELS_DISABLED); }
        if (!catalogs.levelRefs().keySet().containsAll(ids)) { throw new ApiException(NOT_FOUND); }
        return ids.stream().distinct().toList();
    }
    /**
     * A new file key must be this club's upload of that purpose (R-16-16, T-16-21); an unchanged key is kept as it is. The claim (a
     * write) waits for {@link Draft#claimFiles()}, after every check of the write.
     */
    private String file(Draft draft, String stored, String requested, UploadPurpose purpose, boolean club) {
        if (requested == null || requested.equals(stored)) { return stored; }
        if (!club) { throw new ApiException(NOT_FOUND); }
        draft.claims.add(() -> files.claim(requested, purpose, draft.id));
        return requested;
    }
    /** R-16-17: a club course names enabled locales of its club; a library course the api's languages. The fallback is the club's default. */
    private LocalizedText localized(Map<String, String> values, OwnerType owner) {
        var enabled = TenantContext.current() != null && owner != OwnerType.AGILITYHUB ? configs.get(TenantContext.require()).club().locales()
                : messages.supportedLocales().stream().map(Locale::toLanguageTag).toList();
        if (values.isEmpty() || !enabled.containsAll(values.keySet())) { throw invalid("name", "LOCALE_NOT_ENABLED"); }
        if (values.values().stream().anyMatch(value -> value == null || value.isBlank())) { throw invalid("name", "VALIDATION_ERROR"); }
        var stripped = new LinkedHashMap<String, String>(); values.forEach((locale, value) -> stripped.put(locale, value.strip()));
        return new LocalizedText(stripped, defaultLocale(stripped));
    }
    private String defaultLocale(Map<String, String> values) {
        if (TenantContext.current() != null) { return configs.get(TenantContext.require()).club().defaultLocale(); }
        String request = LocaleContext.current().toLanguageTag();
        return values.containsKey(request) ? request : values.keySet().iterator().next();
    }
    private static ApiException invalid(String field, String code) {
        return new ApiException(VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", code))));
    }

    /** `CourseUpdated.diff`: `{field: {before, after}}` for each changed field; the model by its course versions, never its JSON. */
    private static Map<String, Object> diff(Course before, Draft after) {
        var diff = new LinkedHashMap<String, Object>();
        change(diff, "name", before.name().values(), after.name.values());
        change(diff, "discipline", before.discipline(), after.discipline);
        change(diff, "source", before.source(), after.source);
        change(diff, "visibility", before.visibility(), after.visibility);
        change(diff, "designerName", before.designerName(), after.designerName);
        change(diff, "designedOn", before.designedOn(), after.designedOn);
        change(diff, "eventName", before.eventName(), after.eventName);
        change(diff, "agilityhubLevel", before.agilityhubLevel(), after.agilityhubLevel);
        change(diff, "levelIds", before.levelIds(), after.levelIds);
        change(diff, "tags", before.tags(), after.tags);
        change(diff, "notes", before.notes(), after.notes);
        change(diff, "sizeCategory", before.sizeCategory(), after.sizeCategory);
        change(diff, "sourceFileKey", before.sourceFileKey(), after.sourceFileKey);
        change(diff, "imageFileKey", before.imageFileKey(), after.imageFileKey);
        change(diff, "thumbnailFileKey", before.thumbnailFileKey(), after.thumbnailFileKey);
        if (after.modelChanged) { change(diff, "normalizedJson", before.version(), before.version() + 1); }
        change(diff, "pathLengthM", before.stats() == null ? 0.0 : before.stats().pathLengthM(), after.pathLengthM == null ? 0.0 : after.pathLengthM);
        return diff;
    }
    private static void change(Map<String, Object> diff, String field, Object before, Object after) {
        if (Objects.equals(before, after)) { return; }
        var entry = new LinkedHashMap<String, Object>();
        entry.put("before", wire(before)); entry.put("after", wire(after));
        diff.put(field, entry);
    }
    private static Object wire(Object value) { return value instanceof Enum<?> || value instanceof LocalDate ? value.toString() : value; }

    private enum EventKind { CourseCreated, CourseImported, CourseUpdated, CourseDeleted, CourseCopied }
    /** R-16-02: a Smarter import is `CourseImported{warnings[]}` (one `UNKNOWN_OBSTACLE` per obstacle course-core could not type), any other creation `CourseCreated`. */
    private void publishCreated(Course course, CourseModelFacts facts) {
        if (course.source() == CourseSource.SMARTER) {
            var warnings = facts.unknownObstacleIds().stream().map(id -> Map.of("code", "UNKNOWN_OBSTACLE", "obstacleId", id)).toList();
            publish(EventKind.CourseImported, course, Map.of("courseId", course.id(), "source", "SMARTER", "warnings", warnings));
        } else {
            publish(EventKind.CourseCreated, course, Map.of("courseId", course.id(), "ownerType", course.ownerType().name(), "source", course.source().name()));
        }
    }
    private void publish(EventKind kind, Course course, Map<String, Object> payload) {
        events.publish(new CourseEvent(CourseEvent.Kind.valueOf(kind.name()), course.clubId(), course.id(), clock.instant(), payload,
                CourseAccess.accountId(), null, DomainEvent.Origin.BACKOFFICE));
    }

    /** The mutable working copy of a course write. */
    private static final class Draft {
        String id; OwnerType ownerType; String clubId; String ownerAccountId; Visibility visibility; LocalizedText name; Discipline discipline;
        String designerName; LocalDate designedOn; String eventName; AgilityHubLevel agilityhubLevel; List<String> levelIds = List.of();
        List<String> tags = List.of(); String notes; CourseSource source; String sourceCourseId; String sourceFileKey; String imageFileKey;
        String thumbnailFileKey; JsonNode normalizedJson; Integer schemaVersion; SizeCategory sizeCategory; long version; Instant createdAt;
        String createdByAccountId; Instant updatedAt; List<CourseHistory> history = List.of(); Instant deletedAt; String updatedByAccountId;
        CourseModelFacts facts; boolean modelChanged; Double pathLengthM; final List<Runnable> claims = new ArrayList<>();
        void claimFiles() { claims.forEach(Runnable::run); claims.clear(); }
        static Draft create(String id, OwnerType owner, String clubId, String ownerAccountId, Instant now, String actor) {
            var draft = new Draft();
            draft.id = id; draft.ownerType = owner; draft.clubId = clubId; draft.ownerAccountId = ownerAccountId;
            draft.createdAt = now; draft.updatedAt = now; draft.createdByAccountId = actor; draft.updatedByAccountId = actor;
            return draft;
        }
        static Draft of(Course c) {
            var d = new Draft();
            d.id = c.id(); d.ownerType = c.ownerType(); d.clubId = c.clubId(); d.ownerAccountId = c.ownerAccountId(); d.visibility = c.visibility();
            d.name = c.name(); d.discipline = c.discipline(); d.designerName = c.designerName(); d.designedOn = c.designedOn(); d.eventName = c.eventName();
            d.agilityhubLevel = c.agilityhubLevel(); d.levelIds = c.levelIds(); d.tags = c.tags(); d.notes = c.notes(); d.source = c.source();
            d.sourceCourseId = c.sourceCourseId(); d.sourceFileKey = c.sourceFileKey(); d.imageFileKey = c.imageFileKey(); d.thumbnailFileKey = c.thumbnailFileKey();
            d.normalizedJson = c.normalizedJson(); d.schemaVersion = c.schemaVersion(); d.sizeCategory = c.sizeCategory(); d.version = c.version();
            d.createdAt = c.createdAt(); d.createdByAccountId = c.createdByAccountId(); d.updatedAt = c.updatedAt(); d.history = c.history() == null ? List.of() : c.history();
            d.deletedAt = c.deletedAt(); d.updatedByAccountId = c.updatedByAccountId(); d.pathLengthM = c.stats() == null ? null : c.stats().pathLengthM();
            d.facts = CourseModelFacts.of(c.normalizedJson(), d.pathLengthM);
            return d;
        }
        Course build() {
            var f = facts == null ? CourseModelFacts.of(normalizedJson, pathLengthM) : facts;
            return new Course(id, ownerType, clubId, ownerAccountId, visibility, name, discipline, designerName, designedOn, eventName, agilityhubLevel,
                    List.copyOf(levelIds), List.copyOf(tags), notes, source, sourceCourseId, sourceFileKey, imageFileKey, thumbnailFileKey, normalizedJson,
                    schemaVersion, f.designLengthM(), f.designWidthM(), f.canvasWidth(), f.canvasHeight(), f.origin(), f.courseGrade(), f.courseType(),
                    sizeCategory, f.stats(), version, createdAt, createdByAccountId, updatedAt, List.copyOf(history), deletedAt, updatedByAccountId);
        }
    }
}
