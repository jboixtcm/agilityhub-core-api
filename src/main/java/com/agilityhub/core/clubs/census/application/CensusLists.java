package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusListProjection;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterOperator;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.shared.application.lists.ListDefinition.Type.*;

@Component
public class CensusLists implements ListProvider {
    private final CensusListProjection projection;
    private final ClubConfigService configs;
    public CensusLists(CensusListProjection projection, ClubConfigService configs) { this.projection = projection; this.configs = configs; }
    @Override public Set<String> keys() { return Set.of("members", "dogs"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account();
        boolean members = key.equals("members");
        var config = configs.get(TenantContext.require());
        var filters = new LinkedHashMap<String, ListDefinition.Field>();
        var sorts = new LinkedHashMap<String, String>();
        var columns = new ArrayList<>(List.of((members
                ? "fullName,dogs,plan,displayStatus,memberNumber,contact,paymentMethod,nextInvoiceDate,familyGroup,joinedAt,leaveDate,bookingBlocked,imageRights,roles,city,postalCode,pendingDocuments,freeTraining,birthDate,gender,idDocument"
                : "name,breed,level,owner,handler,freeTraining,licenses,displayStatus,sex,age,chip,pendingDocuments,levelAssignedAt,pack,registeredAt").split(",")));
        add(filters, "id", "_id", TEXT);
        if (members) {
            add(filters, "memberNumber", "memberNumber", NUMBER);
            add(filters, "lastName", "lastName", TEXT); contains(filters, "fullName", "fullName");
            for (String field : List.of("status", "planId", "priceId", "familyGroupId", "gender")) { add(filters, field, field, TEXT); }
            add(filters, "displayStatus", "displayStatus.kind", TEXT);
            add(filters, "paymentMethodType", "paymentMethod.type", TEXT);
            add(filters, "roles", "roles", TEXT); add(filters, "city", "address.city", TEXT); add(filters, "postalCode", "address.postalCode", TEXT);
            for (String field : List.of("nextInvoiceDate", "leaveDate", "birthDate")) { add(filters, field, field, DATE); }
            add(filters, "joinedAt", "joinedAt", INSTANT);
            add(filters, "bookingBlocked", "bookingBlock.active", BOOLEAN);
            add(filters, "imageRightsGranted", "consents.imageRights.granted", BOOLEAN);
            add(filters, "dogLevelId", "dogs.levelId", TEXT); contains(filters, "dogName", "dogs.name");
            add(filters, "hasPendingDocuments", "hasPendingDocuments", BOOLEAN);
            add(filters, "freeTrainingAllowed", "dogs.freeTrainingAllowed", BOOLEAN);
            for (String field : List.of("lastName", "firstName", "memberNumber", "joinedAt", "leaveDate", "nextInvoiceDate")) { sorts.put(field, field); }
            sorts.put("city", "address.city");
        } else {
            contains(filters, "name", "name"); contains(filters, "breed", "breed"); contains(filters, "ownerName", "owner.fullName");
            contains(filters, "handlerName", "handlerName");
            for (String field : List.of("levelId", "memberId", "status", "sex", "chip")) { add(filters, field, field, TEXT); }
            for (String field : List.of("freeTrainingAllowed", "hasLicense", "hasPendingDocuments")) { add(filters, field, field, BOOLEAN); }
            add(filters, "licenseOrganisation", "licenses.organisation", TEXT); add(filters, "birthDate", "birthDate", DATE);
            add(filters, "registeredAt", "registeredAt", INSTANT); add(filters, "levelAssignedAt", "levelAssignedAt", INSTANT);
            for (String field : List.of("name", "breed", "registeredAt", "levelAssignedAt")) { sorts.put(field, field); }
            sorts.put("levelOrder", "level.order"); sorts.put("ownerLastName", "owner.lastName1");
        }
        if (!config.modules().contains(Module.BILLING) || !ListAccess.admin()) {
            columns.removeAll(List.of("plan", "paymentMethod", "nextInvoiceDate", "pack"));
            filters.keySet().removeAll(List.of("planId", "priceId", "paymentMethodType", "nextInvoiceDate")); sorts.remove("nextInvoiceDate");
        }
        if (!ListAccess.admin()) { columns.removeAll(List.of("imageRights", "idDocument", "birthDate", "gender", "roles")); filters.keySet().removeAll(List.of("imageRightsGranted", "birthDate", "gender", "roles")); }
        if (!config.modules().contains(Module.FAMILY_GROUP)) { columns.remove("familyGroup"); filters.remove("familyGroupId"); }
        if (!config.modules().contains(Module.FREE_TRAINING)) { columns.remove("freeTraining"); filters.remove("freeTrainingAllowed"); }
        if (!config.modules().contains(Module.PACKS)) { columns.remove("pack"); }
        if (!Boolean.TRUE.equals(config.get("levels.enabled", Boolean.class))) { columns.remove("level"); filters.remove("levelId"); filters.remove("dogLevelId"); sorts.remove("levelOrder"); }
        var defaults = (members ? List.of("fullName", "dogs", "plan", "displayStatus")
                : List.of("name", "breed", "level", "owner", "freeTraining", "licenses", "displayStatus")).stream().filter(columns::contains).toList();
        var fields = new HashSet<>(columns); fields.addAll(List.of("id", "version"));
        if (members && !ListAccess.admin()) { fields.addAll(List.of("firstName", "lastName1", "lastName2", "contactEmails", "phones", "address", "status")); }
        var definition = new ListDefinition(key, filters, sorts,
                members ? List.of("fullName", "idDocument.number", "contactEmails.email", "phones.number", "dogs.name") : List.of("name", "owner.fullName", "chip"),
                columns, defaults, members ? List.of("lastName,asc", "firstName,asc") : List.of("name,asc"), fields);
        return projection.dataset(definition, members, ListAccess.admin());
    }
    private static void add(Map<String, ListDefinition.Field> fields, String key, String path, ListDefinition.Type type) {
        fields.put(key, new ListDefinition.Field(path, type));
    }
    private static void contains(Map<String, ListDefinition.Field> fields, String key, String path) {
        fields.put(key, new ListDefinition.Field(path, TEXT, Set.of(FilterOperator.contains)));
    }
}
