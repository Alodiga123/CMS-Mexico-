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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Apache Fineract (Mifos) implementation of the core contract, over its REST API.
 *
 * <p>Fineract savings accounts support a native two-phase flow: {@code holdAmount}
 * reserves, {@code releaseAmount} undoes, and {@code withdrawal} moves money. That is
 * exactly the shape the authorizer needs, so the debit-with-core product delegates the
 * reservation to the core instead of keeping a shadow balance.
 *
 * <p>Client and account creation follow the payloads the existing Alodiga core
 * integration already uses against this same Mifos (office, legal form, savings
 * product, approve + activate on the same day).
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
    /** Fineract makes paymentTypeId mandatory on deposits and withdrawals. */
    private final long paymentTypeId;
    private final long savingsProductId;
    private final long officeId;

    public FineractCoreBankingClient(
            @Value("${core.fineract.base-url}") String baseUrl,
            @Value("${core.fineract.tenant:default}") String tenant,
            @Value("${core.fineract.username}") String username,
            @Value("${core.fineract.password}") String password,
            @Value("${core.fineract.payment-type-id:1}") long paymentTypeId,
            @Value("${core.fineract.savings-product-id:1}") long savingsProductId,
            @Value("${core.fineract.office-id:1}") long officeId,
            RestClient.Builder builder) {
        this.http = builder
                .baseUrl(baseUrl)
                .defaultHeader("Fineract-Platform-TenantId", tenant)
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
        this.paymentTypeId = paymentTypeId;
        this.savingsProductId = savingsProductId;
        this.officeId = officeId;
        log.info("Core banking client: FINERACT at {} (tenant {}, paymentTypeId {}, savingsProductId {}, officeId {})",
                baseUrl, tenant, paymentTypeId, savingsProductId, officeId);
    }

    // ---------------------------------------------------------------- money

    @Override
    public BigDecimal availableBalance(String accountId) {
        return balances(accountId).availableBalance();
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

    // ------------------------------------------------------------- accounts

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> findClientByExternalId(String externalId) {
        Map<String, Object> body = call(() -> http.get()
                .uri("/clients?externalId={ext}", externalId)
                .retrieve().body(Map.class));
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("pageItems", List.of());
        return items.stream()
                .filter(c -> externalId.equals(String.valueOf(c.get("externalId"))))
                .map(c -> String.valueOf(c.get("id")))
                .findFirst();
    }

    @Override
    public String createClient(String firstName, String lastName, String externalId) {
        String today = todayUtc();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("locale", "en");
        p.put("dateFormat", "dd MMMM yyyy");
        p.put("officeId", officeId);
        p.put("legalFormId", 1);
        p.put("firstname", firstName);
        p.put("lastname", lastName);
        p.put("externalId", externalId);
        p.put("active", true);
        p.put("activationDate", today);
        p.put("submittedOnDate", today);
        Map<String, Object> body = call(() -> http.post()
                .uri("/clients").contentType(MediaType.APPLICATION_JSON).body(p)
                .retrieve().body(Map.class));
        String id = String.valueOf(body.get("clientId"));
        log.info("Fineract client {} created for externalId {}", id, externalId);
        return id;
    }

    @Override
    public String openSavingsAccount(String clientId, String externalId) {
        String today = todayUtc();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("locale", "en");
        p.put("dateFormat", "dd MMMM yyyy");
        p.put("clientId", Long.valueOf(clientId));
        p.put("productId", savingsProductId);
        p.put("externalId", externalId);
        p.put("submittedOnDate", today);
        p.put("nominalAnnualInterestRate", 0);
        p.put("allowOverdraft", false);
        p.put("enforceMinRequiredBalance", false);
        p.put("withdrawalFeeForTransfers", false);
        p.put("interestCalculationDaysInYearType", 365);
        p.put("interestCalculationType", 1);
        p.put("interestCompoundingPeriodType", 1);
        p.put("interestPostingPeriodType", 4);
        Map<String, Object> created = call(() -> http.post()
                .uri("/savingsaccounts").contentType(MediaType.APPLICATION_JSON).body(p)
                .retrieve().body(Map.class));
        String savingsId = String.valueOf(created.get("savingsId"));

        Map<String, Object> approve = Map.of("locale", "en", "dateFormat", "dd MMMM yyyy", "approvedOnDate", today);
        call(() -> http.post().uri("/savingsaccounts/{id}?command=approve", savingsId)
                .contentType(MediaType.APPLICATION_JSON).body(approve).retrieve().body(Map.class));
        Map<String, Object> activate = Map.of("locale", "en", "dateFormat", "dd MMMM yyyy", "activatedOnDate", today);
        call(() -> http.post().uri("/savingsaccounts/{id}?command=activate", savingsId)
                .contentType(MediaType.APPLICATION_JSON).body(activate).retrieve().body(Map.class));
        log.info("Fineract savings account {} opened and activated for client {} ({})", savingsId, clientId, externalId);
        return savingsId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean accountIsActive(String accountId) {
        try {
            Map<String, Object> body = call(() -> http.get()
                    .uri("/savingsaccounts/{id}", accountId)
                    .retrieve().body(Map.class));
            Map<String, Object> status = (Map<String, Object>) body.get("status");
            return status != null && Boolean.TRUE.equals(status.get("active"));
        } catch (BusinessException e) {
            return false; // 404 or unreachable: not something we can link to
        }
    }

    // ----------------------------------------------------------- statements

    @Override
    @SuppressWarnings("unchecked")
    public List<CoreTransaction> transactions(String accountId) {
        Map<String, Object> body = account(accountId, "transactions");
        List<Map<String, Object>> txs = (List<Map<String, Object>>) body.getOrDefault("transactions", List.of());
        List<CoreTransaction> out = new ArrayList<>(txs.size());
        for (Map<String, Object> t : txs) {
            Map<String, Object> type = (Map<String, Object>) t.get("transactionType");
            String code = type != null ? String.valueOf(type.get("code")) : "";
            CoreTxType kind = code.endsWith(".onHold") ? CoreTxType.HOLD
                    : code.endsWith(".release") ? CoreTxType.RELEASE
                    : code.endsWith(".withdrawal") ? CoreTxType.WITHDRAWAL
                    : code.endsWith(".deposit") ? CoreTxType.DEPOSIT
                    : CoreTxType.OTHER;
            Object rel = t.get("releaseTransactionId");
            String releaseRef = rel != null && !"0".equals(String.valueOf(rel)) && !"null".equals(String.valueOf(rel))
                    ? String.valueOf(rel) : null;
            out.add(new CoreTransaction(
                    String.valueOf(t.get("id")), kind,
                    new BigDecimal(String.valueOf(t.get("amount"))),
                    date((List<Number>) t.get("date")),
                    Boolean.TRUE.equals(t.get("reversed")),
                    releaseRef,
                    t.get("note") != null ? String.valueOf(t.get("note")) : null));
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CoreBalances balances(String accountId) {
        Map<String, Object> body = account(accountId, "summary");
        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        Object available = summary != null ? summary.get("availableBalance") : null;
        Object balance = summary != null ? summary.get("accountBalance") : null;
        if (available == null || balance == null) {
            throw new BusinessException("CORE_UNEXPECTED_RESPONSE",
                    "Fineract returned no balances for account " + accountId, HttpStatus.BAD_GATEWAY);
        }
        return new CoreBalances(new BigDecimal(balance.toString()), new BigDecimal(available.toString()));
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> account(String accountId, String associations) {
        return call(() -> http.get()
                .uri("/savingsaccounts/{id}?associations={a}", accountId, associations)
                .retrieve().body(Map.class));
    }

    private String transact(String accountId, BigDecimal amount, String reference, String command) {
        Map<String, Object> payload = datedPayload(accountId, amount);
        payload.put("paymentTypeId", paymentTypeId);
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
    LocalDate operationDate(String accountId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate last = transactions(accountId).stream()
                .map(CoreTransaction::date)
                .filter(d -> d != null)
                .max(LocalDate::compareTo)
                .orElse(today);
        return last.isAfter(today) ? last : today;
    }

    private static LocalDate date(List<Number> ymd) {
        return ymd != null && ymd.size() == 3
                ? LocalDate.of(ymd.get(0).intValue(), ymd.get(1).intValue(), ymd.get(2).intValue())
                : null;
    }

    private static String todayUtc() {
        return LocalDate.now(ZoneOffset.UTC).format(FINERACT_DATE);
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
