package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.clubs.followup.persistence.*;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentService {
    public record Upload(String uploadUrl, String fileKey, Instant expiresAt, Map<String,String> headers) { }
    public record File(String id, String name, String fileKey, String mimeType, long sizeBytes, Instant uploadedAt, String uploadedByAccountId) { }
    private final UploadGrantRepository grants; private final AttachmentRepository attachments; private final AttachmentStorage storage;
    private final EventPublisher events;
    private final ClubConfigService configs; private final DogOwnerAccess dogs; private final Clock clock;
    public AttachmentService(UploadGrantRepository grants, AttachmentRepository attachments, AttachmentStorage storage, ClubConfigService configs, DogOwnerAccess dogs, Clock clock, EventPublisher events) {
        this.events = events;
        this.grants = grants; this.attachments = attachments; this.storage = storage; this.configs = configs; this.dogs = dogs; this.clock = clock;
    }
    private ClubConfig config() { return configs.get(TenantContext.require()); }
    private String account() { var user = CurrentUser.current(); if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); } return user.accountId(); }
    private void validate(String purpose, String type, long size) {
        if (!Set.of("DOG_DOCUMENT", "DOG_PHOTO", "INSTRUCTOR_NOTE").contains(purpose)) { throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH); }
        if ("INSTRUCTOR_NOTE".equals(purpose) && !config().modules().contains(Module.TASKS)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        boolean allowed = type != null && config().get("files.allowedTypes", List.class).stream().anyMatch(raw -> {
            String item = raw.toString(); return item.endsWith("/*") ? type.startsWith(item.substring(0, item.length() - 1)) : type.equals(item);
        });
        if (!allowed || (purpose.equals("DOG_PHOTO") && !type.startsWith("image/"))) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        int max = config().get(purpose.equals("DOG_PHOTO") ? "files.dogPhotoMaxMb" : "files.maxSizeMb", Integer.class);
        if (size <= 0 || size > max * 1024L * 1024) { throw new ApiException(ErrorCode.FILE_TOO_LARGE, Map.of("maxSizeMb", max)); }
    }
    public Upload upload(String purpose, String name, String type, long size) {
        validate(purpose, type, size);
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("\r") || name.contains("\n")) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        String id = UUID.randomUUID().toString(); Instant expires = clock.instant().plusSeconds(300);
        var grant = new UploadGrant(id, TenantContext.require(), account(), purpose, name, type, size, clock.instant(), expires, null);
        grants.insert(grant);
        return new Upload(storage.uploadUrl(id, type, size, expires), id, expires, Map.of("Content-Type", type, "If-None-Match", "*"));
    }
    @Transactional
    public File claim(String key, String purpose, String entity) {
        var grant = grant(key);
        if (!grant.accountId().equals(account()) || !grant.purpose().equals(purpose)
                || (grant.boundEntity() != null && !grant.boundEntity().equals(entity))) { throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH); }
        if (grant.boundEntity() == null && !grant.expiresAt().isAfter(clock.instant())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        validate(purpose, grant.mimeType(), grant.sizeBytes());
        var actual = storage.metadata(key);
        if (actual.sizeBytes() != grant.sizeBytes()) { throw new ApiException(ErrorCode.FILE_TOO_LARGE); }
        if (!Objects.equals(actual.mimeType(), grant.mimeType())) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        grants.bind(key, entity);
        return new File(key, grant.fileName(), key, grant.mimeType(), grant.sizeBytes(), clock.instant(), account());
    }
    private UploadGrant grant(String key) { return grants.findById(key).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public String url(String key, String name) { return key == null ? null : storage.downloadUrl(key, name, clock.instant().plusSeconds(300)); }
    public void putLocal(String id, long expires, String signature, String type, InputStream input) throws IOException {
        if (!(storage instanceof LocalAttachmentStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        var grant = grant(id); if (!grant.accountId().equals(account())) { throw new ApiException(ErrorCode.FORBIDDEN); }
        local.authorize(id, expires, signature, "PUT");
        if (grant.boundEntity() != null || !grant.expiresAt().isAfter(clock.instant())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        if (!grant.mimeType().equals(type)) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        local.put(id, type, grant.sizeBytes(), input);
    }
    public InputStream openLocal(String id, long expires, String signature) throws IOException {
        if (!(storage instanceof LocalAttachmentStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        var grant = grant(id); local.authorize(id, expires, signature, "GET");
        if (grant.boundEntity() == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return local.open(id);
    }
    @Transactional
    @com.agilityhub.core.platform.application.audit.Audited(action = com.agilityhub.core.platform.application.audit.AuditAction.DOG_UPDATED,
            entityType = "'Attachment'", entity = "#key")
    public Map<String,Object> addNote(String entityType, String dogId, String key, String name) {
        if (!"INSTRUCTOR_NOTE".equals(entityType)) { throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH); }
        dogs.requireDog(dogId, true, true);
        if (!config().modules().contains(Module.TASKS)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        if (name == null || name.isBlank() || name.length() > 80) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        attachments.lock(entityType, dogId);
        var existing = attachments.forKey(key).orElse(null);
        if (existing != null) {
            if (!dogId.equals(existing.entityId()) || !account().equals(existing.uploadedByAccountId())) { throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH); }
            return view(existing);
        }
        if (attachments.forEntity(entityType, dogId).size() >= config().get("files.maxAttachmentsPerEntity", Integer.class)) { throw new ApiException(ErrorCode.ATTACHMENT_LIMIT_REACHED); }
        var file = claim(key, entityType, dogId);
        var saved = attachments.insert(new Attachment(key, TenantContext.require(), entityType, dogId, key,
                name, file.mimeType(), file.sizeBytes(), account(), clock.instant(), clock.instant(), null, null, 0));
        var user = CurrentUser.current();
        events.publish(new com.agilityhub.core.clubs.followup.domain.AttachmentAdded(TenantContext.require(), saved.id(), clock.instant(),
                Map.of("attachmentId", saved.id(), "entityType", entityType, "entityId", dogId),
                user.impersonation() == null ? user.accountId() : user.impersonation().actorAccountId(),
                user.impersonation() == null ? null : user.impersonation().memberId(), user.origin()));
        return view(saved);
    }
    public List<Map<String,Object>> noteAttachments(String dogId) { return attachments.forEntity("INSTRUCTOR_NOTE", dogId).stream().map(this::view).toList(); }
    private Map<String,Object> view(Attachment attachment) {
        return Map.of("id", attachment.id(), "name", attachment.name(), "mimeType", attachment.mimeType(), "sizeBytes", attachment.sizeBytes(),
                "url", url(attachment.fileKey(), attachment.name()), "uploadedAt", attachment.createdAt());
    }
}
