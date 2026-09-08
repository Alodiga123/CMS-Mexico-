package bank.cardissuing.standin;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Stand-in as the console sees it: limits, what is owed to the core, and a way to settle it now. */
@RestController
@RequestMapping("/api/standin")
@RequiredArgsConstructor
public class StandInController {

    private final StandInService standIn;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() { return ResponseEntity.ok(standIn.status()); }

    @PostMapping("/settle")
    public ResponseEntity<Map<String, Object>> settle() { return ResponseEntity.ok(Map.of("settled", standIn.settlePending())); }
}
