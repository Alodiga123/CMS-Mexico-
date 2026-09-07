package bank.cardissuing.hsm.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/hsm")
@RequiredArgsConstructor
public class HsmController {

    private final HsmService hsmService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getHsmStatus() {
        boolean online = hsmService.isHsmOnline();
        Map<String, Object> res = new HashMap<>();
        res.put("online", online);
        res.put("serviceUrl", "http://localhost:8080");
        res.put("tcpPort", 1500);
        res.put("hsmType", "PayShield 10K Simulator (Simulador-HSM-Alodiga)");
        res.put("supportedBlockFormats", new String[]{"ISO-0", "ISO-1", "ISO-3"});
        return ResponseEntity.ok(res);
    }

    @GetMapping("/keys")
    public ResponseEntity<java.util.List<HsmService.HsmKeyInfo>> getHsmKeys() {
        return ResponseEntity.ok(hsmService.getAllHsmKeys());
    }

    @PostMapping("/crypto/generate")
    public ResponseEntity<HsmService.HsmCardCryptoResult> generateCrypto(
            @RequestParam(defaultValue = "453211") String bin,
            @RequestParam(defaultValue = "7777") String last4,
            @RequestParam(defaultValue = "ISO-0") String pinBlockFormat,
            @RequestParam(defaultValue = "PVK-01") String pvkIndex) {
        
        HsmService.HsmCardCryptoResult result = hsmService.generateCardCryptograms(bin, last4, pinBlockFormat, pvkIndex);
        return ResponseEntity.ok(result);
    }
}
