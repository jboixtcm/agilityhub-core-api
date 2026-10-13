package com.agilityhub.core.platform.application;

import java.time.Instant;
import java.util.Map;

/** Existing attachment storage through a platform-owned port; every call uses the explicit tenant. */
public interface ConsoleThemeFileAccess {
    @io.swagger.v3.oas.annotations.media.Schema(name="Upload")
    record Upload(String uploadUrl,String fileKey,Instant expiresAt,Map<String,String> headers) { }
    Upload upload(String name,String mime,long size);
    void claim(String key,String entity);
    String url(String key);
}
