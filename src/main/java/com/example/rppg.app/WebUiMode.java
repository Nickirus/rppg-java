package com.example.rppg.app;

import com.example.rppg.web.WebUiApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.HashMap;
import java.util.Map;

final class WebUiMode {
    private WebUiMode() {
    }

    static boolean run() {
        try {
            Map<String, Object> properties = new HashMap<>();
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "8080");
            properties.put("spring.main.banner-mode", "off");
            new SpringApplicationBuilder(WebUiApplication.class)
                    .properties(properties)
                    .run();
            return true;
        } catch (Exception e) {
            System.err.println("Web mode failed to start.");
            System.err.println("Details: " + e.getMessage());
            return false;
        }
    }
}
