package bank.cardissuing.funds.core;

import bank.cardissuing.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stand-in for the core, so the authorizer, issuance and reconciliation can
 * be developed and tested without the real Mifos being reachable. Active unless
 * {@code core.mode=fineract}.
 *
 * <p>Every unknown account starts with {@code core.simulated.default-balance}; accounts
 * opened through {@link #openSavingsAccount} start at zero, like a real new account.
 * Holds are tracked per reference so release and withdraw behave like the real thing,
 * and every operation is journaled so {@link #transactions} reads like a statement.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "core.mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedCoreBankingClient implements CoreBankingClient {

    @org.springframework.beans.factory.annotation.Autowired
    private CoreOutageSwitch outage;

    private static final class SimTx {
        final String id; final CoreTxType type; final BigDecimal amount; final LocalDate date; final String note;
        String releaseRef;
        SimTx(String id, CoreTxType type, BigDecimal amount, String note) {
            this.id = id; this.type = type; this.amount = amount; this.date = LocalDate.now(); this.note = note;
        }
        CoreTransaction view() { return new CoreTransaction(id, type, amount, date, false, releaseRef, note); }
    }

    private final BigDecimal defaultBalance;
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> heldByAccount = new ConcurrentHashMap<>();
    private final Map<String, String[]> holds = new ConcurrentHashMap<>(); // ref -> {account, amount}
    private final Map<String, List<SimTx>> journal = new ConcurrentHashMap<>();
    private final Map<String, String> clientsByExternalId = new ConcurrentHashMap<>();
    private final AtomicLong nextClient = new AtomicLong(1000);
    private final AtomicLong nextAccount = new AtomicLong(5000);
    private final AtomicLong nextTx = new AtomicLong(1);

    public SimulatedCoreBankingClient(@Value("${core.simulated.default-balance:10000}") BigDecimal defaultBalance) {
        this.defaultBalance = defaultBalance;
        log.info("Core banking client: SIMULATED (default balance {})", defaultBalance);
    }

    private SimTx journal(String accountId, CoreTxType type, BigDecimal amount, String note) {
        SimTx tx = new SimTx("SIM-" + nextTx.getAndIncrement(), type, amount, note);
        journal.computeIfAbsent(accountId, k -> new ArrayList<>()).add(0, tx);
        return tx;
    }

    @Override
    public BigDecimal availableBalance(String accountId) {
        if (outage != null) outage.guard();
        BigDecimal balance = balances.computeIfAbsent(accountId, k -> defaultBalance);
        return balance.subtract(heldByAccount.getOrDefault(accountId, BigDecimal.ZERO));
    }

    @Override
    public synchronized String holdAmount(String accountId, BigDecimal amount, String reference) {
        if (outage != null) outage.guard();
        if (availableBalance(accountId).compareTo(amount) < 0) {
            throw new BusinessException("CORE_INSUFFICIENT_FUNDS",
                    "Core reports insufficient funds on account " + accountId, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        SimTx tx = journal(accountId, CoreTxType.HOLD, amount, "Card authorization " + reference);
        holds.put(tx.id, new String[]{accountId, amount.toPlainString()});
        heldByAccount.merge(accountId, amount, BigDecimal::add);
        return tx.id;
    }

    @Override
    public synchronized void releaseHold(String accountId, String holdRef) {
        if (outage != null) outage.guard();
        String[] h = holds.remove(holdRef);
        if (h == null) return; // already released or never existed: releasing is idempotent
        heldByAccount.merge(h[0], new BigDecimal(h[1]).negate(), BigDecimal::add);
        SimTx release = journal(h[0], CoreTxType.RELEASE, new BigDecimal(h[1]), null);
        journal.getOrDefault(h[0], List.of()).stream().filter(t -> t.id.equals(holdRef)).findFirst()
                .ifPresent(t -> t.releaseRef = release.id);
    }

    @Override
    public synchronized String withdraw(String accountId, BigDecimal amount, String reference) {
        if (outage != null) outage.guard();
        balances.merge(accountId, amount.negate(), BigDecimal::add);
        return journal(accountId, CoreTxType.WITHDRAWAL, amount, reference).id;
    }

    @Override
    public synchronized String deposit(String accountId, BigDecimal amount, String reference) {
        if (outage != null) outage.guard();
        balances.merge(accountId, amount, BigDecimal::add);
        return journal(accountId, CoreTxType.DEPOSIT, amount, reference).id;
    }

    @Override
    public Optional<String> findClientByExternalId(String externalId) {
        if (outage != null) outage.guard();
        return Optional.ofNullable(clientsByExternalId.get(externalId));
    }

    @Override
    public String createClient(String firstName, String lastName, String externalId) {
        if (outage != null) outage.guard();
        String id = String.valueOf(nextClient.getAndIncrement());
        clientsByExternalId.put(externalId, id);
        return id;
    }

    @Override
    public String openSavingsAccount(String clientId, String externalId) {
        if (outage != null) outage.guard();
        String id = String.valueOf(nextAccount.getAndIncrement());
        balances.put(id, BigDecimal.ZERO); // a new account has no money until someone deposits
        return id;
    }

    @Override
    public boolean accountIsActive(String accountId) {
        if (outage != null) outage.guard();
        return accountId != null && !accountId.isBlank();
    }

    @Override
    public synchronized List<CoreTransaction> transactions(String accountId) {
        if (outage != null) outage.guard();
        return journal.getOrDefault(accountId, List.of()).stream().map(SimTx::view).toList();
    }

    @Override
    public CoreBalances balances(String accountId) {
        if (outage != null) outage.guard();
        BigDecimal balance = balances.computeIfAbsent(accountId, k -> defaultBalance);
        return new CoreBalances(balance, balance.subtract(heldByAccount.getOrDefault(accountId, BigDecimal.ZERO)));
    }

    /** Test hook: a debit the CMS knows nothing about, like a teller withdrawal. */
    public String externalWithdrawal(String accountId, BigDecimal amount, String note) {
        return withdraw(accountId, amount, note);
    }

    /** Test hook: a release done in the core behind the CMS's back. */
    public void externalRelease(String accountId, String holdRef) {
        releaseHold(accountId, holdRef);
    }

    static String uuid() { return UUID.randomUUID().toString().substring(0, 8); }
}
