package com.agilityhub.core.platform.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped boundary of the `messageTemplates` section of a club definition (S11 §8 and R-11-01, E7-T03): each entry
 * `{code}` names a notification code whose product-seed template the club gets in its own languages. A code the club already
 * has a template of is left as the club edited it (D9 is the source of truth once seeded); a code without a template (an
 * unknown, `SYSTEM` or later-stage one) is `VALIDATION_ERROR`. Codes left out are seeded on first use anyway.
 */
public interface ClubTemplateProvisioner {
    /** The listed codes the current club has no template of yet, in the definition's order (validated). */
    List<String> plan(JsonNode messageTemplates);
    /** Seeds those codes for the current club, inside the caller's transaction. */
    void provision(List<String> codes, List<String> locales, String defaultLocale);
    /** The club's catalog templates as definition entries, in the catalog's order (round-trips through `club:apply`). */
    List<Map<String, Object>> export();
}
