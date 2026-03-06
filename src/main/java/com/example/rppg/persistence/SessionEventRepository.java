package com.example.rppg.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEventEntity, Long> {
    List<SessionEventEntity> findBySessionIdOrderByEventTimeAsc(Long sessionId);
}
