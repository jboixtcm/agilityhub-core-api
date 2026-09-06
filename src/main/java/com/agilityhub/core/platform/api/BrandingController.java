package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.domain.Pwa;
import com.agilityhub.core.shared.application.TenantContext;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirements
public class BrandingController {
    private final ClubConfigService configs;
    private final ObjectMapper mapper;
    public BrandingController(ClubConfigService configs, ObjectMapper mapper) { this.configs = configs; this.mapper = mapper; }
    @GetMapping("/api/v1/branding")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "304", description = "Not modified",
                content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<BrandingResponse> branding(WebRequest request) throws JsonProcessingException {
        return response(BrandingResponse.from(configs.get(TenantContext.require())), request);
    }
    @GetMapping(value = "/api/v1/manifest.webmanifest", produces = "application/manifest+json")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "304", description = "Not modified",
                content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<Manifest> manifest(WebRequest request) throws JsonProcessingException {
        var club = configs.get(TenantContext.require()).club(); var pwa = club.pwa();
        return response(new Manifest(pwa.name(), pwa.shortName(), club.theme().colors().primary(),
                club.theme().colors().background(), pwa.icons(), "/", "standalone"), request);
    }
    private <T> ResponseEntity<T> response(T body, WebRequest request) throws JsonProcessingException {
        String tag;
        try { tag = "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(body))) + "\""; }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        boolean notModified = request.checkNotModified(tag);
        var builder = ResponseEntity.status(notModified ? 304 : 200)
                .eTag(tag).cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .varyBy("Host", "X-Club-Host", "Authorization");
        return builder.body(notModified ? null : body);
    }
    public record Manifest(String name, @JsonProperty("short_name") String shortName,
                           @JsonProperty("theme_color") String themeColor, @JsonProperty("background_color") String backgroundColor,
                           List<Pwa.Icon> icons, @JsonProperty("start_url") String startUrl, String display) { }
}
