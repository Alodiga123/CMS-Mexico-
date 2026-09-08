package bank.cardissuing.disputes.application;

import bank.cardissuing.disputes.domain.DisputeReason;
import bank.cardissuing.disputes.infrastructure.DisputeReasonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads the reason catalog once. Windows are the schemes' usual calendar-day clocks;
 * they are data, not code, so compliance can adjust them without a release.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeReasonSeeder implements CommandLineRunner {

    private final DisputeReasonRepository reasons;

    @Override
    public void run(String... args) {
        if (reasons.count() > 0) return;
        reasons.saveAll(List.of(
                new DisputeReason("10.3", "Fraude: tarjeta presente (VISA)", "VISA", 120, 30, 45, false),
                new DisputeReason("10.4", "Fraude: tarjeta no presente (VISA)", "VISA", 120, 30, 45, true),
                new DisputeReason("11.3", "Sin autorización (VISA)", "VISA", 75, 30, 45, false),
                new DisputeReason("12.6", "Procesamiento duplicado (VISA)", "VISA", 120, 30, 45, false),
                new DisputeReason("13.1", "Mercancía o servicio no recibido (VISA)", "VISA", 120, 30, 45, true),
                new DisputeReason("13.3", "No conforme a lo descrito o defectuoso (VISA)", "VISA", 120, 30, 45, true),
                new DisputeReason("13.6", "Crédito no procesado (VISA)", "VISA", 120, 30, 45, true),
                new DisputeReason("4834", "Error de procesamiento en punto de interacción (Mastercard)", "MASTERCARD", 90, 45, 45, false),
                new DisputeReason("4837", "Fraude sin autorización del tarjetahabiente (Mastercard)", "MASTERCARD", 120, 45, 45, false),
                new DisputeReason("4853", "Disputa del tarjetahabiente (Mastercard)", "MASTERCARD", 120, 45, 45, true),
                new DisputeReason("AC-01", "Aclaración doméstica: cargo no reconocido (CID cap. XIX)", "ANY", 90, 30, 45, false),
                new DisputeReason("AC-02", "Aclaración doméstica: cargo duplicado (CID cap. XIX)", "ANY", 90, 30, 45, false)
        ));
        log.info("Dispute reason catalog seeded ({} codes)", reasons.count());
    }
}
