package com.example.rppg.ingest;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rppg.grpc", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GrpcIngestServerRunner {
    private final GrpcServerProperties grpcServerProperties;
    private final TimelineIngestGrpcService timelineIngestGrpcService;
    private Server server;

    @PostConstruct
    void start() {
        try {
            server = ServerBuilder.forPort(grpcServerProperties.getPort())
                    .addService(timelineIngestGrpcService)
                    .build()
                    .start();
            log.info("gRPC ingest server started on port {}", grpcServerProperties.getPort());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start gRPC ingest server", e);
        }
    }

    @PreDestroy
    void stop() {
        if (server == null) {
            return;
        }
        server.shutdown();
        log.info("gRPC ingest server stopped.");
    }
}
