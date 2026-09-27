package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.followup.domain.CensusForeignEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupEvent;
import com.agilityhub.core.clubs.followup.domain.FollowupRules;
import com.agilityhub.core.clubs.followup.persistence.TaskRepository;
import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.DomainEventHandler;
import com.agilityhub.core.shared.application.FollowupCensusAccess;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * S10 §8 / CATALEG_NOTIFICACIONS, produced only by outbox consumers (durable names `notifications.N-20` …), with TASKS on:
 * N-20 `TaskCreated` → the dog's owner (the task's `memberId`, never the family group), APP + EMAIL (PERSONAL), action
 * OPEN_TASKS; N-21 `TaskCompleted` (whoever completed it) and N-22 `MemberNoteChanged` (a note with text) → every active
 * `Instructor` of the club, APP, action OPEN_DOG. Rendered in each recipient's language; ids are
 * `eventId:recipient:channel`, so a replayed event notifies once. Nothing for TaskUpdated, TaskDeleted, TaskReopened,
 * attachments or observations.
 */
@Service
public class FollowupNotifications {
    private final TaskRepository tasks; private final FollowupCensusAccess census; private final PlanningCatalogAccess catalogs;
    private final NotificationAccounts accounts; private final SystemNotificationService notifications; private final ClubConfigService configs;
    public FollowupNotifications(TaskRepository tasks, FollowupCensusAccess census, PlanningCatalogAccess catalogs, NotificationAccounts accounts,
            SystemNotificationService notifications, ClubConfigService configs) {
        this.tasks = tasks; this.census = census; this.catalogs = catalogs; this.accounts = accounts; this.notifications = notifications; this.configs = configs;
    }
    private boolean enabled(String clubId) { return configs.get(clubId).modules().contains(Module.TASKS); }
    private Map<String, Object> variables(String dogName, String dogId, String action) {
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", configs.get(TenantContext.require()).club().name()); values.put("dog_name", dogName == null ? "" : dogName);
        values.put("action", action); values.put("entityId", dogId);
        return values;
    }

    /** N-20: the owner gets the new task on APP, and by email at the member's contact address. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void taskCreated(String eventId, FollowupEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            if (!enabled(event.clubId())) { return; }
            var task = tasks.findById(event.aggregateId()).filter(found -> found.deletedAt() == null).orElse(null); if (task == null) { return; }
            String memberId = Objects.toString(event.payload().get("memberId"), task.memberId());
            var member = census.members(List.of(memberId)).get(memberId); if (member == null) { return; }
            var dog = census.dog(task.dogId()).orElse(null);
            var values = variables(dog == null ? null : dog.name(), task.dogId(), "OPEN_TASKS");
            values.put("instructor_name", task.createdBy() == null ? "" : task.createdBy().displayName()); values.put("task_excerpt", FollowupRules.excerpt(task.text()));
            var account = member.accountId() == null ? null : accounts.find(member.accountId()).orElse(null);
            String key = eventId + ":" + memberId, locale = account == null || account.locale() == null ? member.locale() : account.locale();
            if (account != null) { notifications.appOnce(key + ":app", "N-20", account.id(), values); }
            if (member.email() != null) {
                if (account != null && member.email().equalsIgnoreCase(account.email())) { notifications.sendOnceLocalized(key + ":email", "N-20", account.id(), locale, values); }
                else { notifications.sendApplicantOnce(key + ":email", "N-20", member.email(), locale, values); }
            }
        }
    }
    /** N-21: every active instructor, whoever completed the task (an instructor too). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void taskCompleted(String eventId, FollowupEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            if (!enabled(event.clubId())) { return; }
            var task = tasks.findById(event.aggregateId()).orElse(null); if (task == null) { return; }
            var values = owner(task.dogId(), task.memberId()); values.put("task_excerpt", FollowupRules.excerpt(task.text()));
            instructors(eventId, "N-21", values);
        }
    }
    /** N-22: every active instructor, when the member's note has text (an emptied note has nothing to read). */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void memberNote(String eventId, CensusForeignEvent event) {
        try (var tenant = TenantContext.open(event.clubId())) {
            if (!enabled(event.clubId())) { return; }
            String dogId = event.text("dogId");
            var note = census.instructorNote(dogId).orElse(null); var dog = census.dog(dogId).orElse(null);
            if (note == null || dog == null || note.text() == null || note.text().isBlank()) { return; }
            instructors(eventId, "N-22", owner(dogId, dog.memberId()));
        }
    }
    /** `member_name` (full name), `dog_name` and the owner's `gender` (for «alumne/alumna», S10 §10). */
    private Map<String, Object> owner(String dogId, String memberId) {
        var dog = census.dog(dogId).orElse(null); var member = census.members(List.of(memberId)).get(memberId);
        var values = variables(dog == null ? null : dog.name(), dogId, "OPEN_DOG");
        values.put("member_name", member == null ? "" : member.fullName()); values.put("gender", member == null || member.gender() == null ? "OTHER" : member.gender());
        return values;
    }
    private void instructors(String eventId, String code, Map<String, Object> values) {
        var memberIds = catalogs.instructorRefs().stream().filter(PlanningCatalogAccess.InstructorRef::active).map(PlanningCatalogAccess.InstructorRef::memberId)
                .filter(Objects::nonNull).distinct().toList();
        var recipients = new TreeSet<String>();
        census.members(memberIds).values().forEach(member -> { if (member.accountId() != null) { recipients.add(member.accountId()); } });
        for (String accountId : recipients) {
            if (accounts.find(accountId).isPresent()) { notifications.appOnce(eventId + ":" + accountId + ":app", code, accountId, values); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Consumers {
        @Bean("notifications.N-20") DomainEventHandler<FollowupEvent> n20(FollowupNotifications service) {
            return handler("TaskCreated", FollowupEvent.class, service::taskCreated);
        }
        @Bean("notifications.N-21") DomainEventHandler<FollowupEvent> n21(FollowupNotifications service) {
            return handler("TaskCompleted", FollowupEvent.class, service::taskCompleted);
        }
        @Bean("notifications.N-22") DomainEventHandler<CensusForeignEvent> n22(FollowupNotifications service) {
            return handler("MemberNoteChanged", CensusForeignEvent.class, service::memberNote);
        }
        private static <T> DomainEventHandler<T> handler(String type, Class<T> eventClass, java.util.function.BiConsumer<String, T> work) {
            return new DomainEventHandler<>() {
                public String eventType() { return type; } public Class<T> eventClass() { return eventClass; }
                public void handle(String id, T event) { work.accept(id, event); }
            };
        }
    }
}
