package com.example.rppg.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "session_events")
@Getter
@Setter
@NoArgsConstructor
public class SessionEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "event_time", nullable = false)
    private OffsetDateTime eventTime;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "bpm")
    private Double bpm;

    @Column(name = "quality")
    private Double quality;

    @Column(name = "payload_json")
    private String payloadJson;
}
