package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContactsWriterPort;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.DemoSeedActor;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * E7-T04 step 4, the S11 half of the demo seed's `messaging` section (after the census half, `clubs.census.application.
 * DemoMessagingSeeder`, order 50, has written the contact data), so S11's channel × audience × preference matrix is visible in
 * 12/D10 and in `bin/e7-smoke`: the bounce mark of an address (the census writer the SendGrid webhook uses, R-11-08), the
 * preferences as D10 saves them (R-11-04), a push device of a fake endpoint registered by the member's own account (R-11-07)
 * and the `CUSTOM` templates as D9 creates them (R-11-12, in the club's languages only). Members are reached through the
 * engine's own ports. Fictional data only; deterministic (the section's values); applied once per club (the planning run).
 */
@Service
public class DemoMessagingSeeder implements DemoSeedStep {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(int member, Map<String, Boolean> emailByCategory, Integer reminderMinutesBefore, Boolean pushClubNews, List<String> bouncedEmails, Push push) {
        public Profile { bouncedEmails = bouncedEmails == null ? List.of() : List.copyOf(bouncedEmails); }
        boolean preferences() { return emailByCategory != null || reminderMinutesBefore != null || pushClubNews != null; }
    }
    /** A push device of the member's account; the endpoint is fake (`push.example.test`): the local fake sender accepts it. */
    public record Push(String endpoint, String p256dh, String auth, String deviceLabel) { }
    public record Template(NotificationCategory category, Map<String, String> title, Map<String, String> body, TemplateIcon icon, TemplateColor color) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spec(List<Profile> profiles, List<Template> customTemplates) {
        public Spec { profiles = profiles == null ? List.of() : List.copyOf(profiles); customTemplates = customTemplates == null ? List.of() : List.copyOf(customTemplates); }
    }
    static final String SECTION = "messaging";
    private final MemberDirectoryPort members; private final MemberContactsWriterPort contacts; private final NotificationPreferencesService preferences;
    private final PushSubscriptionService pushes; private final MessageTemplateService templates; private final ClubConfigService configs; private final ObjectMapper mapper;

    public DemoMessagingSeeder(MemberDirectoryPort members, MemberContactsWriterPort contacts, NotificationPreferencesService preferences, PushSubscriptionService pushes,
            MessageTemplateService templates, ClubConfigService configs, ObjectMapper mapper) {
        this.members = members; this.contacts = contacts; this.preferences = preferences; this.pushes = pushes; this.templates = templates; this.configs = configs;
        this.mapper = mapper;
    }
    @Override public int order() { return 51; }

    @Override public Map<String, Integer> apply(Input input) {
        var counts = new LinkedHashMap<String, Integer>();
        for (String key : List.of("messagingProfiles", "messagingBounces", "pushSubscriptions", "customTemplates")) { counts.put(key, 0); }
        var section = input.specification().get(SECTION);
        if (section == null) { return counts; }
        var spec = mapper.convertValue(section, Spec.class);
        for (var profile : spec.profiles()) {
            String memberId = input.member(profile.member());
            for (String address : profile.bouncedEmails()) {
                if (contacts.markEmailBounced(memberId, address)) { counts.merge("messagingBounces", 1, Integer::sum); }
            }
            if (profile.preferences()) {
                preferences.saveMember(memberId, new NotificationPreferencesService.Patch(categories(profile.emailByCategory()), profile.reminderMinutesBefore() != null,
                        profile.reminderMinutesBefore(), profile.pushClubNews()));
                counts.merge("messagingProfiles", 1, Integer::sum);
            }
            if (profile.push() != null) {
                String account = members.find(memberId).map(contact -> contact.accountId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, Map.of("member", profile.member())));
                var push = profile.push();
                DemoSeedActor.as(account, "MEMBER", () -> pushes.subscribe(push.endpoint(), push.p256dh(), push.auth(), push.deviceLabel(), "AgilityHub demo seed"));
                counts.merge("pushSubscriptions", 1, Integer::sum);
            }
        }
        var locales = configs.get(TenantContext.require()).club().locales();
        for (var template : spec.customTemplates()) {
            // The members' row only (a CUSTOM notice never reaches the staff, S11 §13-6); D9 refuses a text in a language the club lacks.
            var matrix = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
            matrix.put(NotificationAudience.MEMBER, Map.of(NotificationChannel.APP, true, NotificationChannel.EMAIL, true, NotificationChannel.SMS, false));
            for (var staff : List.of(NotificationAudience.INSTRUCTORS, NotificationAudience.ADMINS)) {
                matrix.put(staff, Map.of(NotificationChannel.APP, false, NotificationChannel.EMAIL, false, NotificationChannel.SMS, false));
            }
            templates.create(new MessageTemplateService.Create(template.category(), new MessageTemplateService.Texts(only(template.title(), locales),
                    only(template.body(), locales), null), template.icon(), template.color(), matrix));
            counts.merge("customTemplates", 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, String> only(Map<String, String> texts, List<String> locales) {
        var kept = new LinkedHashMap<String, String>();
        texts.forEach((locale, text) -> { if (locales.contains(locale)) { kept.put(locale, text); } });
        return kept;
    }
    private static Map<NotificationCategory, Boolean> categories(Map<String, Boolean> values) {
        if (values == null) { return null; }
        var categories = new EnumMap<NotificationCategory, Boolean>(NotificationCategory.class);
        values.forEach((key, value) -> categories.put(NotificationCategory.valueOf(key), value));
        return categories;
    }
}
