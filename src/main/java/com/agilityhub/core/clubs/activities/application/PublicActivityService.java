package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.persistence.*;
import com.agilityhub.core.clubs.activities.domain.*;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import com.github.benmanes.caffeine.cache.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PublicActivityService {
    public record Result(Map<String,Object> value,String language) { }
    private record Key(String clubId,String locale,String selector) { }
    private final Cache<Key,Map<String,Object>> cache=Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofSeconds(300)).build();
    private final PublicClubAccess clubs; private final ActivityRepository activities; private final ActivityContext context;
    private final ActivityProjection projection; private final AttachmentStorage storage;
    public PublicActivityService(PublicClubAccess clubs,ActivityRepository activities,ActivityContext context,ActivityProjection projection,AttachmentStorage storage) {
        this.clubs=clubs; this.activities=activities; this.context=context; this.projection=projection; this.storage=storage;
    }
    /** The API key is checked first: without a valid key the caller cannot tell whether the club exists or has ACTIVITIES on. */
    private ClubConfig club(String slug,String key,boolean needsKey) {
        ClubConfig config;
        if(!needsKey) config=clubs.resolveClub(slug);
        else try { config=clubs.resolve(slug,key); }
        catch(ApiException e) { if(e.code()==ErrorCode.CLUB_NOT_FOUND) throw new ApiException(ErrorCode.INVALID_API_KEY); throw e; }
        if(!config.modules().contains(Module.ACTIVITIES)) throw new ApiException(ErrorCode.MODULE_DISABLED);
        return config;
    }
    public Result read(String clubSlug,String key,String language,String scope,String slug) {
        var club=club(clubSlug,key,true); var locale=PublicClubLocale.resolve(language,club);
        if(slug==null && !Set.of("upcoming","past").contains(scope)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        try(var tenant=TenantContext.open(club.club().id()); var ignored=LocaleContext.open(locale)) {
            var value=com.agilityhub.core.shared.application.CacheLoads.get(cache,new Key(club.club().id(),locale.toLanguageTag(),slug==null?"list:"+scope:"slug:"+slug),k -> {
                if(slug!=null) return projection.publicActivity(publicActivity(slug));
                Instant now=context.clock.instant(); Instant earliest=now.atZone(context.zone()).minusMonths(12).toInstant();
                return ActivityProjection.object("items",activities.findAll().stream().filter(a -> "upcoming".equals(scope)
                        ? a.state()==ActivityState.PUBLISHED && !context.times(a).endsAt().isBefore(now)
                        : a.state()==ActivityState.FINISHED && !context.times(a).endsAt().isBefore(earliest) && !context.times(a).endsAt().isAfter(now))
                        .sorted(Comparator.comparing(a -> context.times(a).startsAt())).map(projection::publicActivity).toList());
            });
            return new Result(value,locale.toLanguageTag());
        }
    }
    private Activity publicActivity(String slug) {
        var a=activities.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if(a.state()==ActivityState.DRAFT) throw new ApiException(ErrorCode.NOT_FOUND); return a;
    }
    public record PublicFile(String clubId,String fileKey,String name,String mimeType,String signedUrl) { }
    public PublicFile file(String clubSlug,String slug,String fileId) {
        var club=club(clubSlug,null,false);
        try(var tenant=TenantContext.open(club.club().id())) {
            var a=publicActivity(slug); String key,name,type;
            if(a.image()!=null && a.image().fileId().equals(fileId)) { key=a.image().fileKey(); name=a.image().name(); type=a.image().mimeType(); }
            else { var file=a.documents().stream().filter(d -> d.id().equals(fileId)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); key=file.fileKey(); name=file.name(); type=file.mimeType(); }
            String url=storage.downloadUrl(key,name,context.clock.instant().plusSeconds(900));
            // Local storage's signed capability is served through the same public-state guard.
            if(url.startsWith("/api/v1/attachments/files/")) url="/api/v1/public/"+clubSlug+"/activities/"+slug+"/files/"+fileId+url.substring(url.indexOf('?'));
            return new PublicFile(club.club().id(),key,name,type,url);
        }
    }
    public void invalidate(String clubId) { cache.asMap().keySet().removeIf(k -> k.clubId().equals(clubId)); }
}
