package bank.cardissuing.funds.core;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The breaker in front of the core: see it, and (test benches only) pull it. */
@RestController
@RequestMapping("/api/core")
@RequiredArgsConstructor
public class CoreOutageController {

    private final CoreOutageSwitch outage;

    public record OutageBody(boolean down) { }

    @GetMapping("/outage")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of("down", outage.isDown(), "forced", outage.isForced()));
    }

    @PostMapping("/outage")
    public ResponseEntity<Map<String, Object>> force(@RequestBody OutageBody b) {
        outage.force(b.down());
        return ResponseEntity.ok(Map.of("down", outage.isDown(), "forced", outage.isForced()));
    }
}
