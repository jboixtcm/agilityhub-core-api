package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.MessagingEvent;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationPreference;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.audit.AuditCommand;
import com.agilityhub.core.platform.application.audit.AuditWriter;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.NotificationAccounts;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * S11 R-11-04, the «Avisos» block of 12 (the member; the impersonation token too) and of D10 (ADMIN): the stored
 * `Member.notificationPreferences` or the product defaults (never persisted on reading), with the reminder options, the
 * account's language, the club's languages and the SMS/PUSH modules. A save is partial (an absent key keeps its value); a
 * reminder outside `messaging.reminderOptionsMinutes` is `422 INVALID_REMINDER_OPTION` (CATALEG_ERRORS rule 0). A save that
 * changes something writes the block, `MEMBER_UPDATED` (`changes[].path = notificationPreferences.…`; no dedicated audit
 * action exists) and `NotificationPreferencesChanged{memberId, diff, byAccountId}` in one transaction. Only the channels a
 * template allows can be removed: the `ChannelResolver` (E7-T02) applies the block; here it is stored and exposed.
 */
@Service
public class NotificationPreferencesService {
    private final MemberDirectoryPort members; private final MemberContactsWriterPort writer; private final MessagingContractAccess access;
    private final NotificationAccounts accounts; private final ClubConfigService configs; private final AuditWriter audit; private final EventPublisher events;
    private final Clock clock; private final MessagingTransactions transactions;

    public NotificationPreferencesService(MemberDirectoryPort members, MemberContactsWriterPort writer, MessagingContractAccess access, NotificationAccounts accounts,
            ClubConfigService configs, AuditWriter audit, EventPublisher events, Clock clock, MessagingTransactions transactions) {
        this.members = members; this.writer = writer; this.access = access; this.accounts = accounts; this.configs = configs; this.audit = audit;
        this.events = events; this.clock = clock; this.transactions = transactions;
    }

    /** A partial save: `null` = absent (kept); `reminderMinutesBefore` present with a `null` value is «Mai». */
    public record Patch(Map<NotificationCategory, Boolean> emailByCategory, boolean reminderPresent, Integer reminderMinutesBefore, Boolean pushClubNews) { }
    public record View(NotificationPreference preferences, List<Integer> reminderOptionsMinutes, String locale, List<String> availableLocales, boolean sms,
            boolean push) { }

    public View mine() { return view(me(), configs.get(TenantContext.require())); }
    /** D10's read (E76): the member's block exactly as {@link #mine()} answers it to that member. */
    public View member(String memberId) {
        access.member(memberId);
        return view(members.find(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)), configs.get(TenantContext.require()));
    }
    public View saveMine(Patch patch) { return transactions.write(() -> save(me(), patch)); }
    public View saveMember(String memberId, Patch patch) {
        access.member(memberId);
        return transactions.write(() -> save(members.find(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)), patch));
    }

    /** The caller's member: the impersonated one, or the member of the caller's account in the club. */
    private MemberContact me() {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) {
            return members.find(user.impersonation().memberId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        }
        return members.byAccount(access.account()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private View save(MemberContact member, Patch patch) {
        var config = configs.get(TenantContext.require());
        var options = options(config);
        if (patch.reminderPresent() && patch.reminderMinutesBefore() != null && !options.contains(patch.reminderMinutesBefore())) {
            throw new ApiException(ErrorCode.INVALID_REMINDER_OPTION, Map.of("field", "reminderMinutesBefore", "options", options));
        }
        var current = NotificationPreference.of(member.preferences());
        var email = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class);
        email.putAll(current.emailByCategory());
        if (patch.emailByCategory() != null) { patch.emailByCategory().forEach((category, on) -> { if (on != null && category.templated()) { email.put(category, on); } }); }
        var next = new NotificationPreference(email, patch.reminderPresent() ? patch.reminderMinutesBefore() : current.reminderMinutesBefore(),
                patch.pushClubNews() == null ? current.pushClubNews() : patch.pushClubNews(), current.updatedAt(), current.updatedByAccountId());
        var before = comparable(current); var after = comparable(next);
        if (before.equals(after)) { return view(member, config, current); }
        var user = CurrentUser.current();
        String by = user == null ? null : user.impersonation() != null ? user.impersonation().actorAccountId() : user.accountId();
        var stored = next.changedBy(by, clock.instant()).toDocument(member.preferences());
        if (!writer.savePreferences(member.memberId(), stored)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        audit.write(new AuditCommand(AuditAction.MEMBER_UPDATED, "Member", member.memberId(), member.memberId(), Map.of("notificationPreferences", before),
                Map.of("notificationPreferences", after), null));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("memberId", member.memberId()); payload.put("diff", TemplateDiff.between(before, after)); payload.put("byAccountId", by);
        events.publish(new MessagingEvent(MessagingEvent.Kind.NotificationPreferencesChanged, TenantContext.require(), member.memberId(), clock.instant(), payload,
                user == null ? null : user.accountId(), user == null || user.impersonation() == null ? null : user.impersonation().memberId(),
                user == null ? DomainEvent.Origin.SYSTEM : Objects.requireNonNullElse(user.origin(), DomainEvent.Origin.APP)));
        return view(member, config, next);
    }

    private View view(MemberContact member, ClubConfig config) { return view(member, config, NotificationPreference.of(member.preferences())); }
    /**
     * `locale` is `Account.locale` as stored (E7-T03 step 6, E7-T06), also when it is not one of the club's languages: the
     * engine renders the member's notices in it (R-11-01), and 12/D10 show it; `availableLocales` stays the club's. Only a
     * member without an account, or an account without a language, reads the club's default (the field is always present).
     */
    private View view(MemberContact member, ClubConfig config, NotificationPreference preferences) {
        String locale = member.accountId() == null ? null : accounts.find(member.accountId()).map(NotificationAccounts.Recipient::locale).orElse(null);
        if (locale == null || locale.isBlank()) { locale = config.club().defaultLocale(); }
        return new View(preferences, options(config), locale, config.club().locales(), config.modules().contains(Module.SMS), config.modules().contains(Module.PUSH));
    }
    /** `messaging.reminderOptionsMinutes`, the choices of «Recordatori de classe» (R-11-04). */
    static List<Integer> options(ClubConfig config) {
        var value = config.get("messaging.reminderOptionsMinutes", List.class);
        if (value == null) { return List.of(); }
        return ((List<?>) value).stream().filter(Number.class::isInstance).map(v -> ((Number) v).intValue()).toList();
    }
    /** The block as the audit and the event compare it: e-mail per category, reminder, push of club news. */
    private static Map<String, Object> comparable(NotificationPreference preference) {
        var email = new LinkedHashMap<String, Object>(); preference.emailByCategory().forEach((category, on) -> email.put(category.name(), on));
        var map = new LinkedHashMap<String, Object>();
        map.put("emailByCategory", email); map.put("reminderMinutesBefore", preference.reminderMinutesBefore()); map.put("pushClubNews", preference.pushClubNews());
        return map;
    }
}
