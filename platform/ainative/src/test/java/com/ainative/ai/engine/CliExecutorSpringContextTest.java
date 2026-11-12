package com.ainative.ai.engine;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CliExecutorSpringContextTest {

    @Test
    void springCreatesCliExecutorWithConfiguredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("cliExecutorTest", Map.of(
                            "ainative.ai.cli-binary", "claude",
                            "ainative.ai.cli-timeout-sec", "300",
                            "ainative.ai.cli-launch-mode", "shell",
                            "ainative.ai.cli-shell", "/bin/bash")));

            context.register(CliExecutor.class);
            context.refresh();

            assertNotNull(context.getBean(CliExecutor.class));
        }
    }
}
