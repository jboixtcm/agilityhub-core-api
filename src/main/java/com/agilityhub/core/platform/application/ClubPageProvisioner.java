package com.agilityhub.core.platform.application;

import java.util.List;
import java.util.Map;

/** Club-as-code content port; callers supply the prospective club locale configuration. */
public interface ClubPageProvisioner {
    record Page(String key, Map<String, String> title, Map<String, String> body, boolean active) { }
    void validate(Page page, String defaultLocale, List<String> locales);
    boolean needsProvision(Page page);
    void provision(Page page, String defaultLocale, List<String> locales);
    List<Page> list();
}
