package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.platform.application.ConsoleThemeFileAccess;
import org.springframework.stereotype.Component;

@Component
public class ConsoleThemeFileAdapter implements ConsoleThemeFileAccess {
    private final AttachmentService files;
    public ConsoleThemeFileAdapter(AttachmentService files) {this.files=files;}
    public Upload upload(String name,String mime,long size) {
        var value=files.upload("CLUB_LOGO",name,mime,size);return new Upload(value.uploadUrl(),value.fileKey(),value.expiresAt(),value.headers());
    }
    public void claim(String key,String entity) {files.claim(key,"CLUB_LOGO",entity);}
    public Image read(String key) {return files.readClubLogo(key);}
}
