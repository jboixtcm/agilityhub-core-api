package com.agilityhub.core.courses.application.ports;

/**
 * R-16-16: the club's private file store for course files (the api's signed-URL store, adapted at the composition root). Keys are
 * `{clubId}/courses/{uuid}`, scoped to the current tenant; URLs are short-lived and authorise themselves (CONVENCIONS_API §5).
 */
public interface CourseFileStore {
    record Upload(String uploadUrl, String fileKey) { }
    /** A signed upload URL for one file of the current club; the caller has checked the purpose, type and size. */
    Upload upload(String purpose, String contentType, long size);
    /**
     * Binds an uploaded file to {@code entity}: the key must be the current club's, of {@code purpose}, uploaded with its declared
     * type and size, and not bound to another entity. Anything else, another club's key included, is `404 NOT_FOUND` (T-16-21).
     */
    void claim(String fileKey, String purpose, String entity);
    /** A short-lived signed download URL of a stored key. */
    String downloadUrl(String fileKey);
}
