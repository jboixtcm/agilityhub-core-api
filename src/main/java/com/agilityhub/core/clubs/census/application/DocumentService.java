package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class DocumentService {
    private final CensusAccess access; private final CensusRepository<DogDocument> documents;
    private final AttachmentService attachments; private final CensusEvents events; private final Clock clock;
    public DocumentService(CensusAccess access, CensusRepository<DogDocument> documents, AttachmentService attachments, CensusEvents events, Clock clock) {
        this.access = access; this.documents = documents; this.attachments = attachments; this.events = events; this.clock = clock;
    }
    public String documentId(String dog, String type) {
        var existing = documents.matching(Criteria.where("dogId").is(dog).and("type").is(type));
        if (!existing.isEmpty()) { return existing.getFirst().id; }
        return UUID.nameUUIDFromBytes((TenantContext.require() + ":" + dog + ":" + type).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private List<Map<String,Object>> catalog() { return rows(access.config().get("census.dogDocumentTypes", List.class)); }
    private Map<String,Object> type(String key) {
        return catalog().stream().filter(row -> key.equals(row.get("key"))).findFirst().orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_TYPE_UNKNOWN));
    }
    private DogDocument document(String dog, String type) {
        var found = documents.matching(Criteria.where("dogId").is(dog).and("type").is(type));
        if (!found.isEmpty()) { return found.getFirst(); }
        var doc = new DogDocument(); doc.id = documentId(dog, type); doc.clubId = TenantContext.require(); doc.dogId = dog;
        doc.type = type; doc.state = "PENDING"; doc.files = new ArrayList<>(); return doc;
    }
    private void save(DogDocument doc) { if (doc.version == null) { documents.insert(doc); } else { documents.save(doc); } }
    public List<Map<String,Object>> list(String dogId) {
        access.dogs.require(dogId); var all = new LinkedHashMap<String,DogDocument>();
        documents.matching(Criteria.where("dogId").is(dogId)).forEach(doc -> all.put(doc.type, doc));
        catalog().stream().filter(row -> Boolean.TRUE.equals(row.get("required")))
                .forEach(row -> all.computeIfAbsent(string(row.get("key")), key -> document(dogId, key)));
        return all.values().stream().map(this::view).toList();
    }
    public List<String> pending(String dogId) {
        var required = catalog().stream().filter(row -> Boolean.TRUE.equals(row.get("required"))).map(row -> row.get("key")).toList();
        return list(dogId).stream().filter(row -> "PENDING".equals(row.get("state")) && required.contains(row.get("type")))
                .map(row -> string(row.get("type"))).toList();
    }
    public Map<String,Object> view(DogDocument doc) {
        var active = rows(doc.files).stream().filter(file -> file.get("removedAt") == null).toList();
        var definition = catalog().stream().filter(row -> doc.type.equals(row.get("key"))).findFirst().orElse(Map.of());
        var labels = map(definition.get("label")); String fallback = access.config().club().defaultLocale();
        String label = string(labels.getOrDefault(LocaleContext.current().getLanguage(), labels.getOrDefault(fallback, doc.type)));
        return object("id", doc.id, "type", doc.type, "typeLabel", label, "state", active.isEmpty() ? "PENDING" : "RECEIVED",
                "files", active.stream().map(file -> object("id", file.get("id"), "name", file.get("name"),
                        "url", attachments.url(string(file.get("fileKey")), string(file.get("name"))), "uploadedAt", instant(file.get("uploadedAt")))).toList(),
                "lastReminderAt", doc.lastReminderAt);
    }
    public String owner(String id) { return access.dogs.require(id).memberId; }
    @Transactional
    @Audited(action = AuditAction.DOG_UPDATED, entityType = "'DogDocument'", entity = "documentId(#dogId, #type)", member = "owner(#dogId)")
    public Map<String,Object> upload(String dogId, String type, String name, String key, boolean own) {
        if (own) { access.ownDog(dogId, true); } else { access.mutableDog(dogId); }
        type(type); name = text(name, "name", 80, true); var doc = document(dogId, type);
        var file = attachments.claim(key, "DOG_DOCUMENT", dogId + ":" + type);
        if (rows(doc.files).stream().anyMatch(row -> key.equals(row.get("fileKey")))) { return view(doc); }
        var files = new ArrayList<>(rows(doc.files)); files.add(object("id", file.id(), "fileKey", key, "name", name,
                "mimeType", file.mimeType(), "sizeBytes", file.sizeBytes(), "uploadedAt", file.uploadedAt(), "uploadedByAccountId", file.uploadedByAccountId()));
        doc.files = files; doc.state = "RECEIVED"; save(doc);
        events.emit("DogDocumentUploaded", "DogDocument", doc.id, object("dogId", dogId, "type", type, "state", "RECEIVED", "fileId", file.id())); return view(doc);
    }
    @Transactional
    @Audited(action = AuditAction.DOG_DOCUMENT_FILE_REMOVED, entityType = "'DogDocument'", entity = "#docId", member = "owner(#dogId)")
    public void remove(String dogId, String docId, String fileId) {
        access.mutableDog(dogId); var doc = documents.require(docId);
        if (!dogId.equals(doc.dogId)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        var files = new ArrayList<Map<String,Object>>(); boolean changed = false;
        for (var row : rows(doc.files)) {
            var file = new LinkedHashMap<>(row);
            if (fileId.equals(file.get("id")) && file.get("removedAt") == null) {
                file.put("removedAt", clock.instant()); file.put("removedByAccountId", CurrentUser.current().accountId()); changed = true;
            }
            files.add(file);
        }
        if (!changed) {
            if (files.stream().noneMatch(file -> fileId.equals(file.get("id")))) { throw new ApiException(ErrorCode.NOT_FOUND); }
            return;
        }
        doc.files = files; doc.state = files.stream().allMatch(file -> file.get("removedAt") != null) ? "PENDING" : "RECEIVED"; documents.save(doc);
        if ("PENDING".equals(doc.state) && Boolean.TRUE.equals(type(doc.type).get("required"))) { pendingEvent(doc, "FILE_REMOVED"); }
    }
    @Transactional
    @Audited(action = AuditAction.DOG_UPDATED, entityType = "'DogDocument'", entity = "documentId(#dogId, #type)", reason = "'DOCUMENT_REMINDER'", member = "owner(#dogId)")
    public void remind(String dogId, String type) {
        access.mutableDog(dogId); var definition = type(type); var doc = document(dogId, type);
        if (!Boolean.TRUE.equals(definition.get("required")) || !"PENDING".equals(view(doc).get("state"))) { throw new ApiException(ErrorCode.DOCUMENT_NOT_PENDING); }
        if (doc.lastReminderAt != null && doc.lastReminderAt.plus(Duration.ofHours(24)).isAfter(clock.instant())) { throw new ApiException(ErrorCode.DOCUMENT_REMINDER_TOO_SOON); }
        doc.lastReminderAt = clock.instant(); save(doc); pendingEvent(doc, "MANUAL");
    }
    @Transactional
    public void registered(String dogId) {
        access.mutableDog(dogId);
        for (var type : catalog()) {
            if (!Boolean.TRUE.equals(type.get("required"))) { continue; }
            var doc = document(dogId, string(type.get("key")));
            if (doc.version == null) { save(doc); pendingEvent(doc, "REGISTRATION"); }
        }
    }
    private void pendingEvent(DogDocument doc, String trigger) {
        events.emit("DogDocumentPending", "DogDocument", doc.id, object("dogId", doc.dogId, "type", doc.type, "state", "PENDING", "trigger", trigger));
    }
    @Transactional
    @Audited(action = AuditAction.DOG_UPDATED, entityType = "'Dog'", entity = "#dogId", member = "owner(#dogId)")
    public String photo(String dogId, String key, boolean own) {
        var dog = own ? access.ownDog(dogId, true) : access.mutableDog(dogId); attachments.claim(key, "DOG_PHOTO", dogId);
        if (!key.equals(dog.photoFileKey)) {
            String before = dog.photoFileKey; dog.photoFileKey = key; access.dogs.save(dog);
            events.emit("DogUpdated", "Dog", dogId, object("dogId", dogId, "memberId", dog.memberId, "diff", object("photoFileKey", object("before", before, "after", key))));
        }
        return attachments.url(key, dog.name);
    }
}
