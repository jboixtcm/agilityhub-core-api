package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Stable public URLs select only logos explicitly published on this club's current theme. */
@Service
public class PlatformThemeFiles {
    private final ConsoleThemeFileAccess files;private final ClubRepository clubs;
    public PlatformThemeFiles(ConsoleThemeFileAccess files,ClubRepository clubs) {this.files=files;this.clubs=clubs;}
    public ConsoleThemeFileAccess.Upload upload(String name,String mime,long size) {return files.upload(name,mime,size);}
    public String claim(Club club,String slot,String key,String previous) {
        if(key==null) return previous;
        if(key.isBlank()) return null;
        String url=path(club.slug(),key);
        if(url.equals(previous)) return previous;
        files.claim(key,club.id()+":"+slot);return url;
    }
    public String download(String slug,String key) {
        var club=clubs.findBySlug(slug).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        String url=path(slug,key);
        if(!Arrays.asList(club.theme().logoUrl(),club.theme().logoDarkUrl(),club.theme().markUrl()).contains(url)) throw new ApiException(ErrorCode.NOT_FOUND);
        try(var scope=TenantContext.open(club.id())) {return files.url(key);}
    }
    private String path(String slug,String key) {
        if(!key.matches("[A-Za-z0-9-]+")) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        return "/api/v1/public/"+slug+"/theme/"+key;
    }
}
