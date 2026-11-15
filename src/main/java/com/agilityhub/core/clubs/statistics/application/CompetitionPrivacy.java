package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.clubs.statistics.persistence.CompetitionImportRepository;
import com.agilityhub.core.clubs.statistics.persistence.CompetitionPreviewRepository;
import com.agilityhub.core.platform.application.privacy.*;
import org.springframework.stereotype.Component;

@Component
public class CompetitionPrivacy implements ErasureStep, MemberDataSource {
    private final CompetitionImportRepository imports; private final CompetitionPreviewRepository previews;
    private final PrivacyTraces traces;
    public CompetitionPrivacy(CompetitionImportRepository imports, CompetitionPreviewRepository previews, PrivacyTraces traces) {
        this.imports = imports; this.previews = previews; this.traces = traces;
    }
    @Override public void contribute(ErasureMember member, MemberDataPackage data) {
        data.section("competitionResults", imports.exportDogs(member.dogIds()));
    }
    @Override public ErasureEffect erase(ErasureSubject subject) {
        // Raw provider previews can contain the subject even before a dog assignment; invalidate this club's drafts.
        long replays = previews.findAll().stream().mapToLong(preview -> traces.forgetReplay(preview.replayAccountId(), preview.replayKey())).sum();
        return ErasureEffect.builder().count("idempotency_records", replays).count("competition_imports", imports.eraseDogs(subject.dogIds()))
                .count("competition_previews", previews.erasePreviews()).build();
    }
}
