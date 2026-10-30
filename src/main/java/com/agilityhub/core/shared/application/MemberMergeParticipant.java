package com.agilityhub.core.shared.application;

import java.util.Map;

/** Owning contexts move operational references; historical documents are never rewritten. */
public interface MemberMergeParticipant {
    Map<String, Long> preview(String sourceMemberId);
    default java.util.List<String> blockers(String sourceMemberId, String targetMemberId) { return java.util.List.of(); }
    void merge(String sourceMemberId, String targetMemberId);
}
