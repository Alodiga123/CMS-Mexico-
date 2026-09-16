package bank.cardissuing.audit.application;

import bank.cardissuing.audit.domain.AuditLog;
import bank.cardissuing.audit.infrastructure.AuditLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Bitácora de auditoría con tamper-evidence (PCI DSS 10.3.2 / 10.5): cada registro guarda el hash
 * del registro anterior (prevHash) y su propio hash (recordHash) sobre su contenido. Así, alterar,
 * borrar o insertar una fila rompe la cadena y {@link #verifyChain()} lo detecta señalando el id.
 *
 * El encadenado se serializa con un lock de proceso y una cabeza en memoria, inicializada desde la
 * BD al arrancar. Es correcto para una instancia (el CMS corre en un contenedor); si se escala a
 * varias réplicas, mover la cabeza a un lock distribuido (Redis/advisory lock de Postgres).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements AuditService {

    private static final String GENESIS = "GENESIS";
    private static final Object CHAIN_LOCK = new Object();

    private final AuditLogRepository auditLogRepository;
    private volatile String head = GENESIS;

    @PostConstruct
    void initChainHead() {
        try {
            AuditLog last = auditLogRepository.findTopByOrderByIdDesc();
            if (last != null && last.getRecordHash() != null) head = last.getRecordHash();
        } catch (Exception e) {
            log.warn("No se pudo inicializar la cabeza de la cadena de auditoría: {}", e.getMessage());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW) // own transaction: an audit row survives a rollback of the caller
    public void log(String action, String entityName, String entityId, String performBy) {
        String username = bank.cardissuing.common.security.CmsPrincipal.auditName(performBy);
        synchronized (CHAIN_LOCK) {
            String prev = head;
            // Truncar a milisegundos: el hash se calcula sobre este valor y debe coincidir con lo que
            // Postgres guarda y devuelve. Con la precisión de nanos de LocalDateTime.now() el valor
            // releído difiere (la BD trunca) y la verificación fallaría por un falso positivo.
            AuditLog newLog = AuditLog.builder()
                    .action(action)
                    .entityName(entityName)
                    .entityId(entityId)
                    .username(username)
                    .timestamp(java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                    .prevHash(prev)
                    .build();
            newLog.setRecordHash(sha256(prev + "|" + canonical(newLog)));
            auditLogRepository.save(newLog);
            head = newLog.getRecordHash();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChainCheck verifyChain() {
        List<AuditLog> all = auditLogRepository.findAllByOrderByIdAsc();
        String expectedPrev = null; // la cadena empieza en el primer registro con recordHash
        long checked = 0;
        for (AuditLog a : all) {
            if (a.getRecordHash() == null) continue; // filas anteriores al encadenado (legado)
            if (expectedPrev == null) expectedPrev = a.getPrevHash(); // ancla en el primer eslabón
            if (!java.util.Objects.equals(a.getPrevHash(), expectedPrev)) {
                return new ChainCheck(false, checked, a.getId(), "eslabón roto: prevHash no coincide con el registro anterior");
            }
            String recomputed = sha256(a.getPrevHash() + "|" + canonical(a));
            if (!recomputed.equals(a.getRecordHash())) {
                return new ChainCheck(false, checked, a.getId(), "registro alterado: el hash no coincide con su contenido");
            }
            expectedPrev = a.getRecordHash();
            checked++;
        }
        return new ChainCheck(true, checked, null, "cadena íntegra");
    }

    /** Contenido canónico que sella cada registro. Cambiar cualquier campo invalida el hash. */
    private static String canonical(AuditLog a) {
        return String.valueOf(a.getAction()) + "|" + a.getEntityName() + "|" + a.getEntityId()
                + "|" + a.getUsername() + "|" + a.getTimestamp();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
