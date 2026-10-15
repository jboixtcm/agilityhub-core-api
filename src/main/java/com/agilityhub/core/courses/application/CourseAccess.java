package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseTypes.*;
import com.agilityhub.core.courses.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * R-16-03 / R-16-16: who reads and who writes a course. Read: a `CLUB` course by the INSTRUCTORs and ADMINs of its own club (a
 * `PRIVATE` one by its author only), a `PUBLIC` library or account course by anyone, a `PRIVATE` account course by its author.
 * Write: a club course by its club's staff (its author when `PRIVATE`), an account course by its author, never the AgilityHub
 * library from a club (`403`). Another club's course, a missing or a deleted one is `404 NOT_FOUND`; a course the caller cannot see
 * is `404 COURSE_NOT_VISIBLE`. A MEMBER also needs `courses.showSetupToMembers` (E9-T01), and also reads, through R-16-16, a course
 * that an `ACTIVE` setup of their club shows, directly or through its placement (E9-T03).
 */
@Component
public class CourseAccess {
    private final CourseRepository courses;
    private final GlobalCourseRepository global;
    private final ClubConfigService configs;
    private final RingSetupRepository setups;
    private final PlacementRepository placements;
    private final GlobalCourseWriter globalWriter;
    public CourseAccess(CourseRepository courses, GlobalCourseRepository global, ClubConfigService configs, RingSetupRepository setups,
            PlacementRepository placements, GlobalCourseWriter globalWriter) {
        this.courses = courses; this.global = global; this.configs = configs; this.setups = setups; this.placements = placements;
        this.globalWriter = globalWriter;
    }
    /**
     * E115 (write skew): a placement or setup that names {@code course} writes the course too, at the version it read, so a concurrent
     * edit or deletion conflicts with it (`409 STALE_VERSION`). The AgilityHub library is never deleted and is shared by every club:
     * it is not written.
     */
    public void reference(Course course) {
        switch (course.ownerType()) {
            case CLUB -> courses.reference(course.id(), course.version());
            case ACCOUNT -> globalWriter.reference(course.id(), course.version());
            case AGILITYHUB -> { }
        }
    }
    public static boolean role(String role) {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    public static boolean staff() { return role("ADMIN") || role("INSTRUCTOR"); }
    public static String accountId() { var user = CurrentUser.current(); return user == null ? null : user.accountId(); }
    public void memberVisibility() { if (!staff() && !enabled("courses.showSetupToMembers")) { throw new ApiException(NOT_FOUND); } }
    public boolean enabled(String key) { return Boolean.TRUE.equals(configs.get(TenantContext.require()).get(key, Boolean.class)); }

    public Course course(String id, boolean write) {
        memberVisibility();
        Course course = courses.findById(id).filter(c -> c.deletedAt() == null).or(() -> global.findById(id)).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (!readable(course) && (write || staff() || !shownBySetup(course))) { throw new ApiException(COURSE_NOT_VISIBLE); }
        if (write && !writable(course)) { throw new ApiException(FORBIDDEN); }
        return course;
    }
    /** R-16-16: an `ACTIVE` setup of the caller's club shows the course, directly or through one of its placements. */
    private boolean shownBySetup(Course course) {
        return TenantContext.current() != null && setups.showsCourse(course.id(), placements.ofCourse(course.id()));
    }
    public static boolean readable(Course course) {
        String me = accountId();
        return switch (course.ownerType()) {
            case CLUB -> staff() && TenantContext.current() != null && TenantContext.current().equals(course.clubId())
                    && (course.visibility() == Visibility.CLUB || course.visibility() == Visibility.PRIVATE && author(course, me));
            case AGILITYHUB -> course.visibility() == Visibility.PUBLIC;
            case ACCOUNT -> course.visibility() == Visibility.PUBLIC || author(course, me);
        };
    }
    public static boolean writable(Course course) {
        return switch (course.ownerType()) {
            case CLUB -> readable(course);
            case AGILITYHUB -> false;
            case ACCOUNT -> author(course, accountId());
        };
    }
    /** The author: the account owner of an account course, the creator of a club course. */
    static boolean author(Course course, String accountId) {
        String author = course.ownerType() == OwnerType.ACCOUNT ? course.ownerAccountId() : course.createdByAccountId();
        return accountId != null && Objects.equals(author, accountId);
    }
}
