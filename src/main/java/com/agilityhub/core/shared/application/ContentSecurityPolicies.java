package com.agilityhub.core.shared.application;

/** The api's `Content-Security-Policy` values (security baseline, E0-T10), in one place. */
public final class ContentSecurityPolicies {
    private ContentSecurityPolicies() { }

    /** Every api response. */
    public static final String API = "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    /**
     * A file download (CONVENCIONS_API §5, E5-T27 step 8, ruling E61): the api's policy plus `sandbox`, so a file opened on the api's
     * origin runs in a unique origin without scripts. It does not stop an `<img>` of another page from showing the file.
     */
    public static final String DOWNLOAD = API + "; sandbox";
}
