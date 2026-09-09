package com.agilityhub.core.identity.application;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

public enum TokenDelivery {
    BODY, COOKIE;

    public static final String SETTING = "agilityhub.token-delivery";

    public static boolean cookie(RegisteredClient client) {
        return client != null && COOKIE.name().equals(client.getClientSettings().getSetting(SETTING));
    }
}
