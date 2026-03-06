package com.example.rppg.web;

import com.example.rppg.persistence.SessionEventRepository;
import com.example.rppg.persistence.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SessionOrchestrationServiceTest {
    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private SessionEventRepository sessionEventRepository;

    private SessionOrchestrationService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void createStartStopLifecycleEndsInDone() {
        service = new SessionOrchestrationService(sessionRepository, sessionEventRepository);

        SessionSummary created = service.createSession("web-test");
        assertEquals("CREATED", created.status());

        SessionSummary started = service.startSession(created.sessionId());
        assertEquals("RUNNING", started.status());

        SessionSummary stopped = service.stopSession(created.sessionId());
        assertEquals("DONE", stopped.status());
        assertNotNull(stopped.endedAtEpochMs());
    }

    @Test
    void stopRequiresRunningStatus() {
        service = new SessionOrchestrationService(sessionRepository, sessionEventRepository);
        SessionSummary created = service.createSession("web-test");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.stopSession(created.sessionId())
        );
        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void createsSseEmitterForExistingSession() {
        service = new SessionOrchestrationService(sessionRepository, sessionEventRepository);
        SessionSummary created = service.createSession("web-test");

        SseEmitter emitter = service.streamSessionEvents(created.sessionId());
        assertNotNull(emitter);
        emitter.complete();
    }
}
