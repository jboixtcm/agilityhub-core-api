package com.agilityhub.core.clubs.content.application;

import com.agilityhub.core.clubs.content.domain.PageContent;
import com.agilityhub.core.clubs.content.persistence.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ClubPageService {
    private final ClubPageRepository pages;
    private final ClubPageWriter writer;
    private final ClubConfigService configs;
    public ClubPageService(ClubPageRepository pages, ClubPageWriter writer, ClubConfigService configs) {
        this.pages = pages; this.writer = writer; this.configs = configs;
    }
    public ClubPage get(String key, boolean drafts) {
        return pages.findByKey(key).filter(page -> drafts || page.active()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    public List<ClubPage> list(Boolean active, boolean drafts) {
        return pages.findAll().stream().filter(page -> drafts || page.active()).filter(page -> !drafts || active == null || page.active() == active)
                .sorted(Comparator.comparing(ClubPage::key)).toList();
    }
    @org.springframework.transaction.annotation.Transactional
    public ClubPage create(String key, Map<String, String> title, Map<String, String> body, boolean active) {
        writer.write(content(key, title, body, active), null); return get(key, true);
    }
    @org.springframework.transaction.annotation.Transactional
    public ClubPage update(String key, Map<String, String> title, Map<String, String> body, Boolean active, int version) {
        var old = get(key, true);
        writer.write(content(key, title == null ? old.title().values() : title, body == null ? old.body().values() : body,
                active == null ? old.active() : active), version);
        return get(key, true);
    }
    private PageContent content(String key, Map<String, String> title, Map<String, String> body, boolean active) {
        var club = configs.get(TenantContext.require()).club();
        return PageContent.validate(key, title, body, active, club.defaultLocale(), club.locales());
    }
}
