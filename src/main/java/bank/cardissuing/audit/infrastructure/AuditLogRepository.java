package bank.cardissuing.audit.infrastructure;

import bank.cardissuing.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Newest first, narrowed by whatever filters the reader gave (null means any). */
    @Query("select a from AuditLog a where (:entity is null or a.entityName = :entity) and (:action is null or a.action = :action)"
            + " and (:user is null or a.username = :user) and (:entityId is null or a.entityId = :entityId) order by a.timestamp desc, a.id desc")
    Page<AuditLog> search(@Param("entity") String entity, @Param("action") String action, @Param("user") String user, @Param("entityId") String entityId, Pageable p);

    @Query("select distinct a.entityName from AuditLog a where a.entityName is not null order by a.entityName")
    List<String> entities();

    @Query("select distinct a.action from AuditLog a where a.action is not null order by a.action")
    List<String> actions();

    @Query("select distinct a.username from AuditLog a where a.username is not null order by a.username")
    List<String> users();
}
