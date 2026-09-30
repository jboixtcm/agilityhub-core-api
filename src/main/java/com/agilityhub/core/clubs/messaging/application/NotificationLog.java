package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListDataset;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.application.lists.ListProvider;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * The club's notification log (S11 R-11-10, `GET /notifications`, ADMIN): the universal list of CONVENCIONS_API §4 over
 * `notifications` with the allowlist {@link MessagingContractAccess#NOTIFICATIONS} (`code`, `category`, `channel` and `status`
 * of any delivery, `memberId` of the recipient, `createdAt`; `createdAt desc` by default), every row of the tenant only — also
 * those without an APP delivery or with nothing sent. The same dataset serves the export (S14 R-14-12) and `filter-values`,
 * whose labels are the localized enum names and the member's name. `SYSTEM` rows have no club and never appear; a row written
 * before E7-T02 that does not tell its audience reads `audience: null`.
 */
@Service
public class NotificationLog implements ListProvider {
    public static final String KEY = "notifications";
    private final MemberDirectoryPort members; private final IcuMessageSource messages;

    public NotificationLog(MemberDirectoryPort members, IcuMessageSource messages) { this.members = members; this.messages = messages; }

    @Override public Set<String> keys() { return Set.of(KEY); }
    @Override public ListDataset dataset(String key) {
        var projection = new LinkedHashMap<String, Object>();
        projection.put("createdAt", "$createdAt"); projection.put("code", "$code"); projection.put("category", "$category");
        projection.put("audience", orNull("$audience"));
        projection.put("recipient", new Document("displayName", new Document("$ifNull", List.of("$recipient.displayName", "")))
                .append("memberId", orNull("$recipient.memberId")).append("email", orNull("$recipient.email")));
        projection.put("channels", new Document("$map", new Document("input", new Document("$ifNull", List.of("$deliveries", List.of())))
                .append("as", "d").append("in", new Document("channel", "$$d.channel").append("status", "$$d.status"))));
        projection.put("readAt", orNull("$readAt"));
        return new ListDataset(MessagingContractAccess.NOTIFICATIONS, "notifications", List.of(), projection,
                Set.of("readAt", "audience", "recipient.memberId", "recipient.email"), this::label).withExportProjection(EXPORT_CELLS);
    }
    /**
     * The export's own cells (S14 R-14-12, E7-T03 round 2): «Destinatari» is the recipient's name — an applicant's address when
     * the row has no name — never an internal id; «Canals» is one `{value, qualifier}` per delivery, the channel and its status
     * as the export's own keys (`export.value.notificationChannel.*`, `export.value.deliveryStatus.*`), which the renderer
     * writes «App (Lliurat); SMS (Error)» in the reader's language.
     */
    static final Map<String, Object> EXPORT_CELLS = Map.of(
            "recipient", new Document("$cond", Arrays.asList(
                    new Document("$gt", Arrays.asList(new Document("$strLenCP", new Document("$ifNull", Arrays.asList("$recipient.displayName", ""))), 0)),
                    "$recipient.displayName", new Document("$ifNull", Arrays.asList("$recipient.email", "")))),
            "channels", new Document("$map", new Document("input", new Document("$ifNull", Arrays.asList("$deliveries", List.of()))).append("as", "d")
                    .append("in", new Document("value", new Document("$concat", Arrays.asList("notificationChannel.", "$$d.channel")))
                            .append("qualifier", new Document("$concat", Arrays.asList("deliveryStatus.", "$$d.status"))))));
    private static Document orNull(String path) { return new Document("$ifNull", Arrays.asList(path, null)); }

    public ListPage<Map<String, Object>> list(ListEngine engine, MultiValueMap<String, String> params) { return engine.list(KEY, params); }
    public FilterValues filterValues(ListEngine engine, String field, MultiValueMap<String, String> params) { return engine.facets(KEY, field, params); }

    /** `filter-values` labels: the enum names in the reader's language, the member's name; any other value is itself. */
    String label(String field, Object value) {
        String text = Objects.toString(value, "");
        var locale = LocaleContext.current();
        return switch (field) {
            case "category" -> messages.getMessage("enums.notificationCategory." + text, null, text, locale);
            case "channel" -> messages.getMessage("enums.notificationChannel." + text, null, text, locale);
            case "status" -> messages.getMessage("enums.deliveryStatus." + text, null, text, locale);
            case "memberId" -> members.find(text).map(MemberContact::displayName).filter(name -> !name.isBlank()).orElse(text);
            default -> text;
        };
    }
}
