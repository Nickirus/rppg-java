package com.example.rppg.ingest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "rppg.ingest")
@Getter
@Setter
public class IngestProperties {
    private int batchSize = 200;
    private List<String> allowedSessionStatuses = new ArrayList<>(List.of("CREATED", "RUNNING", "ACTIVE"));
}
