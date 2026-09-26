package com.agilityhub.core.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * E5-T24 (CONVENCIONS_API §5, amended 26-09 after the web's E4-W13; A31): the local storage's signed file URLs authorise
 * themselves, as S3's presigned URLs do. On these routes the bearer is never read (an `<img>` cannot send one, and the web
 * never sends it to the storage), no tenant comes from the host or a bearer, and the service takes the club from the signed
 * file: `PUT /api/v1/attachments/uploads/{id}`, `GET /api/v1/attachments/files/{id}` and `GET /api/v1/signup/files`.
 * `PUT /api/v1/signup/uploads` is not one of them: it keeps the signup form's host and its member bearer (R-04-27).
 */
public final class SignedFileRequests {
    private static final Pattern UPLOAD = Pattern.compile("/api/v1/attachments/uploads/[^/]+");
    private static final Pattern DOWNLOAD = Pattern.compile("/api/v1/attachments/files/[^/]+");
    private SignedFileRequests() { }

    public static boolean matches(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (request.getMethod()) {
            case "PUT" -> UPLOAD.matcher(path).matches();
            case "GET" -> DOWNLOAD.matcher(path).matches() || path.equals("/api/v1/signup/files");
            default -> false;
        };
    }
}
