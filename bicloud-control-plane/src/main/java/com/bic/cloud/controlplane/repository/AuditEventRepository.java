package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.AuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Cursor-based (beforeId) paging; all filters are optional/null-aware.
     *
     * @param ownerScope null (admin) means all events; otherwise only that owner's projects
     * @param projectId  restrict to one project (null -> all)
     * @param action     restrict to one event type (null -> all)
     * @param beforeId   events with a smaller id (null -> start from newest)
     */
    @Query("""
        SELECT a FROM AuditEvent a
        WHERE (:beforeId  IS NULL OR a.id < :beforeId)
          AND (:projectId IS NULL OR a.projectId = :projectId)
          AND (:action    IS NULL OR a.action = :action)
          AND (:ownerScope IS NULL OR a.ownerName = :ownerScope)
        ORDER BY a.id DESC
    """)
    List<AuditEvent> findFeed(
            @Param("ownerScope") String ownerScope,
            @Param("projectId")  Long projectId,
            @Param("action")     AuditEvent.AuditAction action,
            @Param("beforeId")   Long beforeId,
            Pageable pageable);
}
