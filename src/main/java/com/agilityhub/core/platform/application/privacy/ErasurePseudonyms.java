package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.shared.application.SignupCapabilities;
import java.util.Base64;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The server key of the S14 pseudonyms: derived from the server capability key (`SIGNUP_CAPABILITY_KEY`, required in staging and
 * prod), so no new secret is needed and the same address always gives the same pseudonym across restarts. `emailHash` is the
 * keyed digest S14 R-14-17 stores on a security event instead of the address.
 */
@Component
public class ErasurePseudonyms {
    private final byte[] key;
    private final SignupCapabilities capabilities;
    public ErasurePseudonyms(SignupCapabilities capabilities) {
        this.capabilities = capabilities;
        this.key = Base64.getUrlDecoder().decode(capabilities.fingerprint("erasure-pseudonym-key"));
    }
    public String pseudonym(String email) { return email == null ? null : ErasurePolicy.pseudonym(email, key); }
    /** R-14-17: never the address, only this keyed digest of it (trimmed and lower-cased). */
    public String emailHash(String email) {
        return email == null || email.isBlank() ? null : capabilities.fingerprint("email|" + email.strip().toLowerCase(Locale.ROOT));
    }
}
