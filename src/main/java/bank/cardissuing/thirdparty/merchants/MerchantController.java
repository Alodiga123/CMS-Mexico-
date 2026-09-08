package bank.cardissuing.thirdparty.merchants;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The merchants the console offers when an operator simulates an authorization. */
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantDirectoryClient directory;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false, defaultValue = "true") boolean activeOnly) {
        MerchantDirectoryClient.Listing l = directory.list(search);
        List<Map<String, Object>> rows = l.merchants().stream()
                .filter(m -> !activeOnly || (!m.blocked() && (m.status() == null || m.status().toLowerCase().startsWith("activ"))))
                .map(m -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", m.id()); v.put("code", m.code()); v.put("name", m.name()); v.put("rfc", m.rfc());
                    v.put("status", m.status()); v.put("blocked", m.blocked()); v.put("kind", m.kind()); v.put("subMid", m.subMid());
                    return v;
                }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", l.available()); m.put("fromCache", l.fromCache()); m.put("detail", l.detail()); m.put("fetchedAt", l.fetchedAt());
        m.put("total", rows.size()); m.put("merchants", rows);
        return ResponseEntity.ok(m);
    }
}
