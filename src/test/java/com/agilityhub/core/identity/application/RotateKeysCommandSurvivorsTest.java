package com.agilityhub.core.identity.application;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** E11-T06 PIT survivor of {@link RotateKeysCommand} (CLI `bin/core identity:rotate-keys`, ADR-010): the command's name. */
class RotateKeysCommandSurvivorsTest {
    @Test void E11_T06_theCommandIsNamedIdentityRotateKeys() {
        assertThat(new RotateKeysCommand(mock(SigningKeys.class)).name()).isEqualTo("identity:rotate-keys");
    }
}
