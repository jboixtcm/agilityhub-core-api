package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.shared.application.CoreCommand;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * `bin/core census:photo-thumbnails --club=slug [--dry-run]` (WEB-05): once, the rendition of every photo the club stored before
 * the lists showed renditions ({@link DogPhotoThumbnails#backfill}). Prints one line per dog (its id and `WOULD_MAKE`, `MADE` or
 * `NOT_MADE`: a photo the server cannot reduce) and a count; never a name, a key or a URL. A dry run writes nothing.
 */
@Component
public class PhotoThumbnailsCommand implements CoreCommand {
    static final String USAGE = "Usage: census:photo-thumbnails --club=slug [--dry-run]";
    private final DogPhotoThumbnails thumbnails; private final MigrationClubAccess clubs;
    public PhotoThumbnailsCommand(DogPhotoThumbnails thumbnails, MigrationClubAccess clubs) { this.thumbnails = thumbnails; this.clubs = clubs; }
    @Override public String name() { return "census:photo-thumbnails"; }
    @Override public void run(ApplicationArguments args) {
        var club = args.getOptionValues("club");
        if (!args.getNonOptionArgs().isEmpty() || !Set.of("core.command", "club", "dry-run").containsAll(args.getOptionNames())
                || club == null || club.size() != 1 || club.getFirst().isBlank()
                || args.containsOption("dry-run") && !args.getOptionValues("dry-run").isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        boolean dryRun = args.containsOption("dry-run");
        var rows = thumbnails.backfill(clubs.resolve(club.getFirst()), dryRun);
        rows.forEach(row -> System.out.println(row.dogId() + " " + row.outcome()));
        long made = rows.stream().filter(row -> row.outcome().equals("MADE")).count();
        System.out.println(dryRun ? rows.size() + " dog photos without a thumbnail (dry run: nothing written)"
                : made + " thumbnails made, " + (rows.size() - made) + " photos without one (dog photos)");
    }
}
