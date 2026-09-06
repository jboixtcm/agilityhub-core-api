package com.agilityhub.core.identity.application;

import com.agilityhub.core.shared.application.CoreCommand;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class RotateKeysCommand implements CoreCommand {
    private final SigningKeys keys;
    public RotateKeysCommand(SigningKeys keys) { this.keys = keys; }
    @Override public String name() { return "identity:rotate-keys"; }
    @Override public void run(ApplicationArguments arguments) {
        if (!keys.persistent()) {
            throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.INVALID_STATE,
                    java.util.Map.of("reason", "OIDC_MASTER_KEY is required to rotate shared signing keys"));
        }
        keys.rotate();
        System.out.println("Signing key rotated; previous key retained for verification.");
    }
}
