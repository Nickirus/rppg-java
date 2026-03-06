package com.example.rppg.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface SessionEventRepository extends JpaRepository<SessionEventEntity, Long> {
    List<SessionEventEntity> findBySessionIdOrderByEventTimeAsc(Long sessionId);

    List<SessionEventEntity> findBySessionIdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long id, Pageable pageable);
}
