package bank.cardissuing.audit.infrastructure;

import bank.cardissuing.audit.domain.AuditLog;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAuditLogs() {
        List<AuditLog> logs = auditLogRepository.findAll();
        List<AuditLogResponse> responses = logs.stream().map(log -> new AuditLogResponse(
                log.getId(),
                log.getAction() != null ? log.getAction() : "N/A",
                log.getEntityName() != null ? log.getEntityName() : "N/A",
                log.getEntityId() != null ? log.getEntityId() : "N/A",
                log.getUsername() != null ? log.getUsername() : "SYSTEM",
                log.getTimestamp() != null ? log.getTimestamp().toString() : "N/A"
        )).collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @Data
    public static class AuditLogResponse {
        private final Long id;
        private final String action;
        private final String entityName;
        private final String entityId;
        private final String username;
        private final String timestamp;
    }
}
