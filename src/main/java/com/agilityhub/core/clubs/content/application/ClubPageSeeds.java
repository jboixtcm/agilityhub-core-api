package com.agilityhub.core.clubs.content.application;

import com.agilityhub.core.clubs.content.domain.PageContent;
import com.agilityhub.core.clubs.content.persistence.ClubPageRepository;
import com.agilityhub.core.platform.application.ClubPageProvisioner;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ClubPageSeeds implements ClubPageProvisioner {
    private final ClubPageRepository pages;
    private final ClubPageWriter writer;
    public ClubPageSeeds(ClubPageRepository pages, ClubPageWriter writer) { this.pages = pages; this.writer = writer; }
    @Override public void validate(Page page, String defaultLocale, List<String> locales) {
        PageContent.validate(page.key(), page.title(), page.body(), page.active(), defaultLocale, locales);
    }
    @Override public boolean needsProvision(Page page) {
        return pages.findByKey(page.key()).map(old -> !old.title().values().equals(page.title())
                || !old.body().values().equals(page.body()) || old.active() != page.active()).orElse(true);
    }
    @Override public void provision(Page page, String defaultLocale, List<String> locales) {
        if (!needsProvision(page)) { return; }
        var content = PageContent.validate(page.key(), page.title(), page.body(), page.active(), defaultLocale, locales);
        writer.write(content, pages.findByKey(page.key()).map(old -> old.version()).orElse(null));
    }
    @Override public List<Page> list() {
        return pages.findAll().stream().sorted(Comparator.comparing(page -> page.key()))
                .map(page -> new Page(page.key(), page.title().values(), page.body().values(), page.active())).toList();
    }
}
