package com.agilityhub.core.migration.application;

import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class MigrationPhotosCommand implements CoreCommand {
    private final MigrationPhotos photos; private final MigrationClubAccess clubs;
    public MigrationPhotosCommand(MigrationPhotos photos, MigrationClubAccess clubs) { this.photos = photos; this.clubs = clubs; }
    @Override public String name() { return "migration:photos"; }
    @Override public void run(ApplicationArguments args) {
        var club = args.getOptionValues("club");
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club", "dry-run").containsAll(args.getOptionNames())
                || club == null || club.size() != 1 || club.getFirst().isBlank()
                || args.containsOption("dry-run") && !args.getOptionValues("dry-run").isEmpty()) {
            throw new IllegalArgumentException("Usage: migration:photos --club=slug [--dry-run]");
        }
        var rows = photos.run(clubs.resolve(club.getFirst()), args.containsOption("dry-run"));
        rows.forEach(row -> System.out.println(row.dogId() + " " + row.outcome()));
        System.out.println(rows.stream().filter(row -> row.outcome().equals("IMPORTED")).count() + " changes (dog photos)");
    }
}
