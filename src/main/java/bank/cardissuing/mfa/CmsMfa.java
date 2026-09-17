package bank.cardissuing.mfa;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Secreto TOTP de un usuario de la consola, cifrado en reposo. Tabla creada por Flyway (V2). */
@Entity
@Table(name = "cms_mfa")
@Getter
@Setter
public class CmsMfa {

    @Id
    private String username;

    /** Secreto TOTP cifrado (AES-GCM); nunca en claro en la BD. */
    @Column(name = "secret_enc", nullable = false)
    private String secretEnc;

    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;
}
