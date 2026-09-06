package com.agilityhub.core.platform.application.audit;

/** Captured synchronously, before transaction callbacks or request cleanup. */
public record AuditActor(String accountId, String name, String role, String impersonatedMemberId,
                         Boolean support, String ip, String userAgent, String traceId) { }
