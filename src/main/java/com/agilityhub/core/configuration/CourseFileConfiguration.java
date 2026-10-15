package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.courses.application.ports.CourseFileStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E9-T02 (S16 R-16-16): the courses' {@link CourseFileStore} over the api's attachment store, at the composition root (`courses`
 * never imports a `clubs` context, `ArchitectureTest.E9_T01_coursesKeepsItsContextBoundary`). Keys live under `{clubId}/courses/`;
 * the grant's purpose is prefixed so that no attachment route can claim a course file, nor a course an attachment.
 */
@Configuration(proxyBeanMethods = false)
public class CourseFileConfiguration {
    static final String SCOPE = "courses";
    static final String PURPOSE_PREFIX = "COURSE_";
    @Bean CourseFileStore courseFileStore(AttachmentService attachments) {
        return new CourseFileStore() {
            @Override public Upload upload(String purpose, String contentType, long size) {
                var upload = attachments.scopedUpload(SCOPE, PURPOSE_PREFIX + purpose, contentType, size);
                return new Upload(upload.uploadUrl(), upload.fileKey());
            }
            @Override public void claim(String fileKey, String purpose, String entity) { attachments.claimScoped(SCOPE, fileKey, PURPOSE_PREFIX + purpose, entity); }
            @Override public String downloadUrl(String fileKey) { return attachments.scopedUrl(fileKey); }
        };
    }
}
