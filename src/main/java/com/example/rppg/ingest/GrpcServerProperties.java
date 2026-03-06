package com.example.rppg.ingest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rppg.grpc")
@Getter
@Setter
public class GrpcServerProperties {
    private boolean enabled = true;
    private int port = 9090;
}
