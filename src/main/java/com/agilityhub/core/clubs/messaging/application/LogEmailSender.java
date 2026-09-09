package com.agilityhub.core.clubs.messaging.application;

import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Local-only sink: no recipient addresses, links, subjects or credentials in logs. */
public final class LogEmailSender implements EmailSender {
    private final Path mailbox;
    private final ObjectMapper mapper;
    public LogEmailSender(Path mailbox, ObjectMapper mapper) {
        this.mailbox = mailbox; this.mapper = mapper;
    }
    @Override public SendResult send(EmailMessage message) {
        String id = "local-" + java.util.UUID.randomUUID();
        if (mailbox != null) {
            Path pending = null;
            try {
                Files.createDirectories(mailbox, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.setPosixFilePermissions(mailbox, PosixFilePermissions.fromString("rwx------"));
                pending = Files.createTempFile(mailbox, ".pending-", ".json",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                mapper.writeValue(pending.toFile(), message);
                Files.move(pending, mailbox.resolve(id + ".json"), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException failure) {
                if (pending != null) {
                    try { Files.deleteIfExists(pending); } catch (java.io.IOException ignored) { /* Keep diagnostic output credential-free. */ }
                }
                return SendResult.failed("Local mailbox write failed");
            }
        }
        LoggerFactory.getLogger(LogEmailSender.class).info("Local email accepted notificationId={}", message.tags().get("notificationId"));
        return SendResult.sent(id);
    }
}
