package com.agilityhub.core.platform.application;

import java.time.Instant;
import java.util.Map;

/** Existing attachment storage through a platform-owned port; every call uses the explicit tenant. */
public interface ConsoleThemeFileAccess {
    @io.swagger.v3.oas.annotations.media.Schema(name="Upload")
    /** INC-53 (E11-T02): published as the same `Upload` schema as the attachments' one, a path of the api on the local store. */
    record Upload(@io.swagger.v3.oas.annotations.media.Schema(format = "uri-reference") String uploadUrl,String fileKey,Instant expiresAt,Map<String,String> headers) { }
    Upload upload(String name,String mime,long size);
    void claim(String key,String entity);
    String url(String key);
}
