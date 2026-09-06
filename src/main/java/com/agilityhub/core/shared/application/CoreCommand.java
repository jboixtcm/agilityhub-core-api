package com.agilityhub.core.shared.application;

import org.springframework.boot.ApplicationArguments;

/** A command runs after infrastructure startup, with no HTTP server. */
public interface CoreCommand {
    String name();
    void run(ApplicationArguments arguments);
}
