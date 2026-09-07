package bank.cardissuing.funds.core;

import bank.cardissuing.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apache Fineract (Mifos) implementation of the core contract, over its REST API.
 *
 * <p>Fineract savings accounts support a native two-phase flow: {@code holdAmount}
 * reserves, {@code releaseAmount} undoes, and {@code withdrawal} moves money. That is
 * exactly the shape the authorizer needs, so the debit-with-core product delegates the
 * reservation to the core instead of keeping a shadow balance.
 *
 * <p>Enabled with {@code core.mode=fineract}. Credentials come from properties and are
 * never logged.
 *
 * <h3>Transaction dates</h3>
 * Fineract rejects any savings transaction dated before the account's last transaction,
 * and it stamps the operations that take no date (release) with its own server-local
 * day -- which can be a day ahead of this JVM. The business-date API is not enabled on
 * the target core, so the operation date is derived from what the core itself reports:
 * the later of today in UTC and the account's newest transaction date. That never runs
 * ahead of the server's day and never falls behind its last movement.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "core.mode", havingValue = "fineract")
public class FineractCoreBankingClient implements CoreBankingClient {

    private static final DateTimeFormatter FINERACT_DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH);

    private final RestClient http;

    public FineractCoreBankingClient(
            @Value("${core.fineract.base-url}") String baseUrl,
            @Value("${core.fineract.tenant:default}") String tenant,
            @Value("${core.fineract.username}") String username,
            @Value("${core.fineract.password}") String password,
            RestClient.Builder builder) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader("Fineract-Platform-TenantId", tenant)
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
        log.info("Core banking client: FINERACT at {} (tenant {})", baseUrl, tenant);
    }

    @Override
    @SuppressWarnings("unchecked")
    public BigDecimal availableBalance(String accountId) {
        Map<String, Object> body = call(() -> http.get()
                .uri("/savingsaccounts/{id}?associations=summary", accountId)
                .retrieve().body(Map.class));
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        Object available = summary != null ? summary.get("availableBalance") : null;
        if (available == null) {
            throw new BusinessException("CORE_UNEXPECTED_RESPONSE",
                    "Fineract returned no availableBalance for account " + accountId, HttpStatus.BAD_GATEWAY);
        }
        return new BigDecimal(available.toString());
    }

    @Override
    public String holdAmount(String accountId, BigDecimal amount, String reference) {
        Map<String, Object> payload = datedPayload(accountId, amount);
        payload.put("reasonForBlock", "Card authorization " + reference);
        Map<String, Object> body = call(() -> http.post()
                .uri("/savingsaccounts/{id}/transactions?command=holdAmount", accountId)
                .contentType(MediaType.APPLICATION_JSON).body(payload)
                .retrieve().body(Map.class));
        return String.valueOf(body.get("resourceId"));
    }

    @Override
    public void releaseHold(String accountId, String holdRef) {
        // releaseAmount takes no date: the core stamps it with its own day.
        call(() -> http.post()
                .uri("/savingsaccounts/{id}/transactions/{tx}?command=releaseAmount", accountId, holdRef)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .retrieve().body(Map.class));
    }

    @Override
    public String withdraw(String accountId, BigDecimal amount, String reference) {
        return transact(accountId, amount, reference, "withdrawal");
    }

    @Override
    public String deposit(String accountId, BigDecimal amount, String reference) {
        return transact(accountId, amount, reference, "deposit");
    }

    private String transact(String accountId, BigDecimal amount, String reference, String command) {
        Map<String, Object> payload = datedPayload(accountId, amount);
        payload.put("note", reference);
        Map<String, Object> body = call(() -> http.post()
                .uri("/savingsaccounts/{id}/transactions?command={cmd}", accountId, command)
                .contentType(MediaType.APPLICATION_JSON).body(payload)
                .retrieve().body(Map.class));
        return String.valueOf(body.get("resourceId"));
    }

    private Map<String, Object> datedPayload(String accountId, BigDecimal amount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("locale", "en");
        p.put("dateFormat", "dd MMMM yyyy");
        p.put("transactionDate", operationDate(accountId).format(FINERACT_DATE));
        p.put("transactionAmount", amount);
        return p;
    }

    /** The later of today (UTC) and the account's newest transaction date, as Fineract reports it. */
    @SuppressWarnings("unchecked")
    LocalDate operationDate(String accountId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> body = call(() -> http.get()
                .uri("/savingsaccounts/{id}?associations=transactions", accountId)
                .retrieve().body(Map.class));
        List<Map<String, Object>> txs = (List<Map<String, Object>>) body.getOrDefault("transactions", List.of());
        LocalDate last = txs.stream()
                .map(t -> (List<Number>) t.get("date"))
                .filter(d -> d != null && d.size() == 3)
                .map(d -> LocalDate.of(d.get(0).intValue(), d.get(1).intValue(), d.get(2).intValue()))
                .max(LocalDate::compareTo)
                .orElse(today);
        return last.isAfter(today) ? last : today;
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            // 4xx from Fineract carries a business reason (insufficient funds, closed account…).
            HttpStatus status = e.getStatusCode().is4xxClientError()
                    ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
            throw new BusinessException("CORE_REJECTED",
                    "Fineract responded " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), status);
        } catch (RuntimeException e) {
            throw new BusinessException("CORE_UNAVAILABLE",
                    "Fineract not reachable: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
