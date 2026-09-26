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
    /** A local signed download (E5-T26): the stored file with its stored name and MIME type, as S3 answers its object. */
    public record Download(String name, String mimeType, long sizeBytes, InputStream content) { }
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
        if (!Set.of("DOG_DOCUMENT", "DOG_PHOTO", "INSTRUCTOR_NOTE", "SIGNUP_DOCUMENT", "ACTIVITY_IMAGE", "ACTIVITY_DOCUMENT", "TASK", "DOG_OBSERVATIONS").contains(purpose)) { throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH); }
        // S10 §9: the three S10 owners (the member's note, tasks, observations) live under TASKS.
        if (Set.of("INSTRUCTOR_NOTE", "TASK", "DOG_OBSERVATIONS").contains(purpose) && !config().modules().contains(Module.TASKS)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        if (purpose.startsWith("ACTIVITY_") && !config().modules().contains(Module.ACTIVITIES)) { throw new ApiException(ErrorCode.MODULE_DISABLED); }
        boolean allowed = type != null && config().get("files.allowedTypes", List.class).stream().anyMatch(raw -> {
            String item = raw.toString(); return item.endsWith("/*") ? type.startsWith(item.substring(0, item.length() - 1)) : type.equals(item);
        });
        if (!allowed || ((purpose.equals("DOG_PHOTO") || purpose.equals("ACTIVITY_IMAGE")) && !type.startsWith("image/"))) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        int max = config().get(purpose.equals("DOG_PHOTO") ? "files.dogPhotoMaxMb" : "files.maxSizeMb", Integer.class);
        if (size <= 0 || size > max * 1024L * 1024) { throw new ApiException(ErrorCode.FILE_TOO_LARGE, Map.of("maxSizeMb", max)); }
    }
    /**
     * R-04-27 (E3-T09): the anonymous signup upload routes close with the form, decided on the committed club and
     * parameters (never a cached configuration). A MEMBER adding a dog keeps them: at the upload URL by the member's bearer,
     * at its PUT by the grant, which carries the member's account (E5-T26).
     */
    private void requireSignupOpen(boolean member) {
        if (member) { return; }
        var config = configs.current(TenantContext.require());
        if (!"ACTIVE".equals(config.club().status()) || !Boolean.TRUE.equals(config.get("signup.enabled", Boolean.class))) { throw new ApiException(ErrorCode.SIGNUP_CLOSED); }
    }
    public Upload signupUpload(String name, String type, long size) {
        var user = CurrentUser.current();
        requireSignupOpen(user != null);
        validate("SIGNUP_DOCUMENT",type,size);
        if (!(type.startsWith("image/") || type.equals("application/pdf"))) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        if (name == null || name.isBlank() || name.length() > 80 || name.contains("\r") || name.contains("\n")) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        String safe = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        String month = java.time.format.DateTimeFormatter.ofPattern("yyyyMM").withZone(java.time.ZoneId.of(config().club().timeZone())).format(clock.instant());
        String key = "signup/"+TenantContext.require()+"/"+month+"/"+UUID.randomUUID()+"/"+safe;
        Instant expires = clock.instant().plusSeconds(900);
        // E5-T26 (R-04-27): a MEMBER's grant (add-dog) carries the account, so that its PUT passes while signup is closed.
        grants.insert(new UploadGrant(key,TenantContext.require(),user==null?null:user.accountId(),"SIGNUP_DOCUMENT",safe,type,size,clock.instant(),expires,null));
        return new Upload(storage.uploadUrl(key,type,size,expires),key,expires,Map.of("Content-Type",type,"If-None-Match","*"));
    }
    /**
     * E5-T19: the `id` of a file claimed at signup is a plain one derived from its key, never the key itself, whose `/` would
     * split `DELETE /dogs/{id}/documents/{docId}/files/{fileId}`. The same key always gives the same id, so a re-claim keeps it.
     */
    public static String signupFileId(String key) { return UUID.nameUUIDFromBytes(("signup-file:" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    @Transactional
    public File claimSignup(String key, String entity) {
        var grant = grants.findById(key).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_FOUND));
        if (!key.startsWith("signup/"+TenantContext.require()+"/") || !"SIGNUP_DOCUMENT".equals(grant.purpose())
                || grant.boundEntity()!=null && !grant.boundEntity().equals(entity)) { throw new ApiException(ErrorCode.FILE_NOT_FOUND); }
        validate("SIGNUP_DOCUMENT",grant.mimeType(),grant.sizeBytes());
        AttachmentStorage.Metadata actual;
        try { actual=storage.metadata(key); } catch (ApiException missing) { if (missing.code()==ErrorCode.NOT_FOUND) throw new ApiException(ErrorCode.FILE_NOT_FOUND); throw missing; }
        if (actual.sizeBytes()!=grant.sizeBytes()) { throw new ApiException(ErrorCode.FILE_TOO_LARGE); }
        if (!actual.mimeType().equals(grant.mimeType())) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        grants.bind(key,entity);
        return new File(signupFileId(key),grant.fileName(),key,grant.mimeType(),grant.sizeBytes(),clock.instant(),null);
    }
    /**
     * E5-T24 (S04 R-04-19, amended 26-09 after the web's E4-W13): a new file in D2's edit of a pending dog is the ADMIN's own
     * `POST /attachments/upload-url` with `purpose = DOG_DOCUMENT`, uploaded in the club and not claimed by another entity.
     * Any other key (another club's, purpose's or account's, claimed for another dog or type, or never uploaded) is not
     * found, as a signup key is: 400 FILE_NOT_FOUND. The file waits for the D2 save, so the URL's five minutes do not apply.
     */
    @Transactional
    public File claimDogDocument(String key, String entity) {
        var grant = grants.findById(key).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_FOUND));
        if (!"DOG_DOCUMENT".equals(grant.purpose()) || !account().equals(grant.accountId())
                || grant.boundEntity() != null && !grant.boundEntity().equals(entity)) { throw new ApiException(ErrorCode.FILE_NOT_FOUND); }
        validate("DOG_DOCUMENT", grant.mimeType(), grant.sizeBytes());
        AttachmentStorage.Metadata actual;
        try { actual = storage.metadata(key); } catch (ApiException missing) { if (missing.code() == ErrorCode.NOT_FOUND) { throw new ApiException(ErrorCode.FILE_NOT_FOUND); } throw missing; }
        if (actual.sizeBytes() != grant.sizeBytes()) { throw new ApiException(ErrorCode.FILE_TOO_LARGE); }
        if (!Objects.equals(actual.mimeType(), grant.mimeType())) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
        grants.bind(key, entity);
        return new File(key, grant.fileName(), key, grant.mimeType(), grant.sizeBytes(), clock.instant(), grant.accountId());
    }
    @FunctionalInterface private interface SignedHandler<T> { T handle(UploadGrant grant) throws IOException; }
    /**
     * E5-T24 (CONVENCIONS_API §5, A31): the local signed URLs authorise themselves. The signature binds the file id, the expiry
     * and the method, so it is checked first (a wrong or expired one is 403 FORBIDDEN); the file, and so its club, is then the
     * one the URL signed, and a bearer, if any, plays no part (the routes read none, {@code SignedFileRequests}).
     * E5-T26 (review E5-T24 #3): the request has no tenant, so the grant's club is opened as the tenant for the handler.
     */
    private <T> T signed(LocalAttachmentStorage local, String id, long expires, String signature, String method, ErrorCode missing,
            SignedHandler<T> handler) throws IOException {
        local.authorize(id, expires, signature, method);
        var grant = grants.signed(id).orElseThrow(() -> new ApiException(missing));
        try (var tenant = TenantContext.open(grant.clubId())) { return handler.handle(grant); }
    }
    /**
     * The signup form's signed upload URL (R-04-08). E5-T26 (R-04-27, CONVENCIONS_API §5): a signed route like the others, for
     * the grant's club whatever the host, with no bearer. An anonymous grant closes with the form; a MEMBER's grant (add-dog)
     * does not.
     */
    public void putSignupLocal(String key,long expires,String signature,String type,InputStream input) throws IOException {
        if (!(storage instanceof LocalAttachmentStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        signed(local, key, expires, signature, "PUT", ErrorCode.FILE_NOT_FOUND, grant -> {
            requireSignupOpen(grant.accountId() != null);
            if (!"SIGNUP_DOCUMENT".equals(grant.purpose()) || grant.boundEntity()!=null || !grant.expiresAt().isAfter(clock.instant())) { throw new ApiException(ErrorCode.FILE_NOT_FOUND); }
            if (!grant.mimeType().equals(type)) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
            local.put(key,type,grant.sizeBytes(),input);
            return null;
        });
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
    /** The local signed upload URL (E5-T24): its signature alone authorises the PUT, for the club of the grant it names. */
    public void putLocal(String id, long expires, String signature, String type, InputStream input) throws IOException {
        if (!(storage instanceof LocalAttachmentStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        signed(local, id, expires, signature, "PUT", ErrorCode.NOT_FOUND, grant -> {
            if (grant.boundEntity() != null || !grant.expiresAt().isAfter(clock.instant())) { throw new ApiException(ErrorCode.INVALID_STATE); }
            if (!grant.mimeType().equals(type)) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
            local.put(id, type, grant.sizeBytes(), input);
            return null;
        });
    }
    /**
     * The local signed download URL (E5-T24): its signature alone authorises the GET of a claimed file, whatever the bearer.
     * E5-T26: it answers the file's stored MIME type and the grant's file name, as S3 answers its object's.
     */
    public Download openLocal(String id, long expires, String signature) throws IOException {
        if (!(storage instanceof LocalAttachmentStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return signed(local, id, expires, signature, "GET", ErrorCode.NOT_FOUND, grant -> {
            if (grant.boundEntity() == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
            var stored = local.metadata(id);
            return new Download(grant.fileName(), stored.mimeType(), stored.sizeBytes(), local.open(id));
        });
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
        int limit = config().get("files.maxAttachmentsPerEntity", Integer.class);
        if (attachments.forEntity(entityType, dogId).size() >= limit) { throw new ApiException(ErrorCode.ATTACHMENT_LIMIT_REACHED, Map.of("max", limit)); }
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
