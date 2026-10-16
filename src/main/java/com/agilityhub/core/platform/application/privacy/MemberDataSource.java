package com.agilityhub.core.platform.application.privacy;

/**
 * S14 R-14-13: one context's sections of a member's data package (`memberData.v1`), read in the current club. Values are JSON-ready
 * (maps, lists, strings, numbers, booleans; instants and dates as ISO strings). IBANs are masked, and no third party's name is
 * included (`auditEntries` carry the actor's role only; `familyGroup` the id and role).
 */
public interface MemberDataSource {
    void contribute(ErasureMember member, MemberDataPackage data);
}
