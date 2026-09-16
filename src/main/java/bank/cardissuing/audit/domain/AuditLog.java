package bank.cardissuing.audit.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Table(name = "AuditLogs")
@Entity
@Getter
@Setter
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String action;

    private String entityName;
    private String entityId;
    private String username;

    //Thêm @Builder.Default vào trường timestamp để khi build nó không bị null.
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // Tamper-evidence (PCI DSS 10.3.2/10.5): cada registro encadena el hash del anterior. Alterar,
    // borrar o insertar una fila rompe la cadena y lo detecta AuditService.verifyChain().
    @Column(length = 64)
    private String prevHash;

    @Column(length = 64)
    private String recordHash;

}
