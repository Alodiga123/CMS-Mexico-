package bank.cardissuing.card.infrastructure;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.hsm.application.CardCryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Endpoint EXCLUSIVO para la app "Emulador de Tarjeta" de laboratorio (Tap-to-Phone / CDCVM):
 * deja que un teléfono de prueba consulte las tarjetas de un titular por su correo y obtenga el PAN
 * de la seleccionada para presentarla por NFC (HCE) al SoftPOS, tal como lo haría un wallet.
 *
 * <p>Es un endpoint de banco de pruebas, NO de producción. Como {@code test-secrets}, expone el PAN
 * en claro, así que está triple-blindado y jamás debe quedar activo en un entorno real:
 * <ol>
 *   <li>Bloqueo duro si el perfil {@code prod} está activo (no depende solo de un flag).</li>
 *   <li>Requiere {@code hsm.host.expose-test-secrets=true} (el mismo gate del banco de pruebas).</li>
 *   <li>Requiere un token compartido en la cabecera {@code X-Emulador-Token} que coincida con
 *       {@code cms.emulador.token}; si esa propiedad está vacía, el endpoint queda deshabilitado.</li>
 * </ol>
 * El PAN completo solo se entrega para la tarjeta puntual seleccionada ({@code /{id}/pan}); el
 * listado por correo devuelve el PAN enmascarado.
 */
@Slf4j
@RestController
@RequestMapping("/api/emulador")
@RequiredArgsConstructor
public class EmuladorController {

    private final CardRepository cardRepository;
    private final CustomerRepository customerRepository;
    private final CardCryptoService cardCrypto;
    private final Environment env;

    @Value("${cms.emulador.token:}")
    private String emuladorToken;

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    /** Aplica el triple blindaje; lanza 404 si el emulador no está habilitado o el token no coincide. */
    private void guard(String token) {
        if (env.acceptsProfiles(Profiles.of("prod"))) {
            throw new BusinessException("EMULADOR_OFF", "no disponible en producción", HttpStatus.NOT_FOUND);
        }
        if (!cardCrypto.exposesTestSecrets()) {
            throw new BusinessException("EMULADOR_OFF", "hsm.host.expose-test-secrets is off", HttpStatus.NOT_FOUND);
        }
        if (emuladorToken == null || emuladorToken.isBlank()) {
            throw new BusinessException("EMULADOR_OFF", "cms.emulador.token no configurado", HttpStatus.NOT_FOUND);
        }
        if (token == null || !emuladorToken.equals(token.trim())) {
            throw new BusinessException("EMULADOR_TOKEN", "token de emulador inválido", HttpStatus.UNAUTHORIZED);
        }
    }

    /** Lista las tarjetas de un titular por correo (PAN enmascarado); para que el emulador las muestre. */
    @GetMapping("/tarjetas")
    public ResponseEntity<List<Map<String, Object>>> tarjetasPorCorreo(
            @RequestHeader(value = "X-Emulador-Token", required = false) String token,
            @RequestParam("correo") String correo) {
        guard(token);
        String email = correo == null ? "" : correo.trim();
        if (email.isEmpty()) {
            throw new BusinessException("EMULADOR_CORREO", "Indica el correo del titular", HttpStatus.BAD_REQUEST);
        }
        List<Customer> titulares = customerRepository.findByEmailIgnoreCase(email);
        List<Map<String, Object>> cards = titulares.stream()
                .flatMap(c -> cardRepository.findByCustomer(c).stream())
                .map(this::resumen)
                .collect(Collectors.toList());
        log.info("[EMULADOR] {} tarjeta(s) para el correo {}", cards.size(), email);
        return ResponseEntity.ok(cards);
    }

    /** PAN completo + vencimiento de la tarjeta seleccionada, para presentarla por NFC (HCE). */
    @GetMapping("/tarjetas/{id}/pan")
    public ResponseEntity<Map<String, Object>> panDeTarjeta(
            @RequestHeader(value = "X-Emulador-Token", required = false) String token,
            @PathVariable Long id) {
        guard(token);
        Card card = cardRepository.findById(id)
                .orElseThrow(() -> new BusinessException("CARD_NOT_FOUND", "Tarjeta " + id + " no encontrada", HttpStatus.NOT_FOUND));
        String pan = cardCrypto.panOf(card)
                .orElseThrow(() -> new BusinessException("CARD_WITHOUT_PAN", "La tarjeta " + id + " no tiene PAN en la bóveda", HttpStatus.UNPROCESSABLE_ENTITY));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardId", card.getId());
        m.put("pan", pan);
        m.put("expiryYYMM", card.getExpiryDate() != null ? card.getExpiryDate().format(YYMM) : "");
        log.info("[EMULADOR] PAN entregado para HCE (tarjeta {}, ****{})", card.getId(), card.getLast4());
        return ResponseEntity.ok(m);
    }

    private Map<String, Object> resumen(Card card) {
        Customer customer = card.getCustomer();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardId", card.getId());
        m.put("titular", customer != null ? customer.getFullName() : "N/A");
        m.put("producto", card.getProduct() != null ? card.getProduct().getProductName() : "Tarjeta");
        m.put("panMasked", "•••• •••• •••• " + (card.getLast4() != null ? card.getLast4() : "????"));
        m.put("last4", card.getLast4());
        m.put("estatus", card.getStatus() != null ? card.getStatus().name() : "N/A");
        m.put("vencimiento", card.getExpiryDate() != null ? card.getExpiryDate().format(YYMM) : "");
        return m;
    }
}
