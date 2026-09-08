package bank.cardissuing.iso8583;

import bank.cardissuing.hsm.application.CardCryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The ISO 8583 link as the console sees it: is it up, is the HSM up, what came through lately. */
@RestController
@RequestMapping("/api/iso")
@RequiredArgsConstructor
public class IsoController {

    private final Iso8583Server server;
    private final IsoSettings settings;
    private final IsoAuthorizationHandler handler;
    private final CardCryptoService crypto;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", settings.isEnabled());
        m.put("listening", server.isRunning());
        m.put("port", settings.getPort());
        m.put("connections", server.connections());
        m.put("hsmUp", crypto.hsmUp());
        m.put("requireHsm", settings.isRequireHsm());
        m.put("verifyCvv", settings.isVerifyCvv());
        m.put("verifyArqc", settings.isVerifyArqc());
        m.put("recent", handler.recent().size());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/recent")
    public ResponseEntity<List<IsoAuthorizationHandler.Trace>> recent() { return ResponseEntity.ok(handler.recent()); }
}
