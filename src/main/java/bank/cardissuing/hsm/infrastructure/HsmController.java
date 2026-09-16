package bank.cardissuing.hsm.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.hsm.application.CardCryptoService;
import bank.cardissuing.hsm.host.HsmHostSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estado del HSM y referencias de llaves configuradas. Habla con el HSM REAL vía
 * {@link CardCryptoService}/PayShield host (comandos BA/DG/EA/CW/CY/KQ). No fabrica criptogramas ni
 * inventarios de llaves ficticios (se retiró el HsmService heredado que usaba Math.random y blobs
 * hardcodeados — A6). Nunca expone PVV/CVV en claro (SAD, PCI DSS 3.2).
 */
@RestController
@RequestMapping("/api/hsm")
@RequiredArgsConstructor
public class HsmController {

    private final CardCryptoService cardCrypto;
    private final HsmHostSettings settings;
    private final CardRepository cardRepository;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", cardCrypto.hsmUp());
        m.put("host", settings.getHost());
        m.put("port", settings.getPort());
        m.put("hsmType", "PayShield host (BA/DG/EA/CW/CY/KQ)");
        return ResponseEntity.ok(m);
    }

    /** Referencias de las llaves que el CMS tiene configuradas (bajo la LMK del HSM). Sin blobs ni KCV falsos. */
    @GetMapping("/keys")
    public ResponseEntity<List<KeyRef>> keys() {
        HsmHostSettings.Keys k = settings.getKeys();
        List<KeyRef> out = new ArrayList<>();
        out.add(new KeyRef("PVK", "PIN Verification Key", "005", "PVK", present(k.getPvk()), "índice PVKI " + k.getPvki()));
        out.add(new KeyRef("CVK-A", "Card Verification Key A", "006", "CVK", present(k.getCvkA()), null));
        out.add(new KeyRef("CVK-B", "Card Verification Key B", "006", "CVK", present(k.getCvkB()), null));
        out.add(new KeyRef("IMK", "Issuer Master Key (ARQC/EMV)", "109", "IMK", present(k.getImk()), null));
        out.add(new KeyRef("ZPK", "Zone PIN Key (por defecto)", "001", "ZPK", present(k.getZpk()), null));
        if (k.getAcquirerZpk() != null) {
            for (Map.Entry<String, String> e : k.getAcquirerZpk().entrySet()) {
                out.add(new KeyRef("ZPK:" + e.getKey(), "Zone PIN Key adquirente " + e.getKey(), "001", "ZPK", present(e.getValue()), null));
            }
        }
        return ResponseEntity.ok(out);
    }

    /** Metadatos NO sensibles del material criptográfico de una tarjeta (nunca PVV/CVV en claro). */
    @GetMapping("/card/{id}")
    public ResponseEntity<Map<String, Object>> cardHsm(@PathVariable Long id) {
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CARD_NOT_FOUND", "Card " + id + " not found", HttpStatus.NOT_FOUND));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardId", card.getId());
        m.put("hsmOnline", cardCrypto.hsmUp());
        m.put("provisioned", card.getPvv() != null);
        m.put("pvki", card.getPvki());
        m.put("cryptoProvisionedAt", card.getCryptoProvisionedAt() != null ? card.getCryptoProvisionedAt().toString() : null);
        m.put("pinBlockFormat", card.getProduct() != null ? card.getProduct().getPinBlockFormat() : "ISO-0");
        m.put("pvkIndex", card.getProduct() != null ? card.getProduct().getPvkIndex() : "PVK-01");
        m.put("cvkIndex", card.getProduct() != null ? card.getProduct().getCvkIndex() : "CVK-A");
        m.put("nota", "PVV/CVV no se exponen aquí (SAD, PCI DSS 3.2)");
        return ResponseEntity.ok(m);
    }

    private static boolean present(String s) { return s != null && !s.isBlank(); }

    /** Referencia de una llave configurada (sin exponer el blob ni un KCV fabricado). */
    public record KeyRef(String id, String label, String keyTypeCode, String keyTypeName, boolean configured, String detalle) { }
}
