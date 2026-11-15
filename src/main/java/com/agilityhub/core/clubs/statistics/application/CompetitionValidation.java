package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

public final class CompetitionValidation {
    public static final int MAX_BYTES = 2_000_000;
    public static final int MAX_ROWS = 2_000;
    private CompetitionValidation() { }
    public static ApiException invalid(String reason) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "competitionImport", "reason", reason));
    }
    public static String url(CompetitionModels.Source source, String value) {
        if (source == null || value == null || value.length() > 500) { throw invalid("INVALID_SOURCE"); }
        try {
            var uri = URI.create(value.trim());
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || !uri.getRawPath().equals(uri.getPath())
                    || (uri.getRawFragment() != null && !(source == CompetitionModels.Source.FLOW_AGILITY && "list_anchor_header".equals(uri.getRawFragment())))) { throw invalid("INVALID_SOURCE_URL"); }
            boolean valid = source == CompetitionModels.Source.FCAG
                    ? "app.fcagility.cat".equals(uri.getHost()) && uri.getPath().matches("/federated/classification/division/[1-9][0-9]{0,8}")
                    : ("www.flowagility.com".equals(uri.getHost()) || "flowagility.com".equals(uri.getHost()))
                        && uri.getPath().matches("/(?:event|zone/event|zone/run|zone/prints/(?:trial|run))/[0-9a-fA-F-]{36}(?:/[a-z_]+)?");
            if (!valid || !uri.normalize().equals(uri)) { throw invalid("INVALID_SOURCE_URL"); }
            if (source == CompetitionModels.Source.FLOW_AGILITY) {
                String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
                if (path.matches("/zone/run/[0-9a-f-]{36}/results")) { path = path.replace("/zone/run/", "/zone/prints/run/"); }
                return "https://www.flowagility.com" + path;
            }
            return uri.toString();
        } catch (IllegalArgumentException failure) { throw invalid("INVALID_SOURCE_URL"); }
    }
    public static void content(String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("EMPTY_OR_TOO_LARGE");
        }
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
