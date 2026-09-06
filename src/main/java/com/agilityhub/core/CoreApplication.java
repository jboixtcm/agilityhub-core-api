package com.agilityhub.core;

import com.agilityhub.core.cli.CoreCli;
import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CoreApplication {
    public static void main(String[] args) {
        boolean command = Arrays.stream(args).anyMatch(arg -> arg.equals("--core.command") || arg.startsWith("--core.command="));
        var app = new SpringApplication(CoreApplication.class);
        if (!command) { app.run(args); return; }
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "ERROR",
                "shared.scheduling.enabled", "false"));
        int status;
        try (var context = app.run(args)) {
            status = context.getBean(CoreCli.class).execute(new DefaultApplicationArguments(args));
        } catch (RuntimeException failure) {
            System.err.println("Command startup failed (" + failure.getClass().getSimpleName() + ")");
            status = 1;
        }
        System.exit(status);
    }
}
