package bank.cardissuing.funds.core;

import bank.cardissuing.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for the core, so the authorizer can be developed and tested
 * without the real Mifos being reachable. Active unless {@code core.mode=fineract}.
 *
 * <p>Every unknown account starts with {@code core.simulated.default-balance}. Holds
 * are tracked per reference so release and withdraw behave like the real thing.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "core.mode", havingValue = "simulated", matchIfMissing = true)
public class SimulatedCoreBankingClient implements CoreBankingClient {

    private final BigDecimal defaultBalance;
    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> heldByAccount = new ConcurrentHashMap<>();
    private final Map<String, String[]> holds = new ConcurrentHashMap<>(); // ref -> {account, amount}

    public SimulatedCoreBankingClient(@Value("${core.simulated.default-balance:10000}") BigDecimal defaultBalance) {
        this.defaultBalance = defaultBalance;
        log.info("Core banking client: SIMULATED (default balance {})", defaultBalance);
    }

    @Override
    public BigDecimal availableBalance(String accountId) {
        BigDecimal balance = balances.computeIfAbsent(accountId, k -> defaultBalance);
        return balance.subtract(heldByAccount.getOrDefault(accountId, BigDecimal.ZERO));
    }

    @Override
    public synchronized String holdAmount(String accountId, BigDecimal amount, String reference) {
        if (availableBalance(accountId).compareTo(amount) < 0) {
            throw new BusinessException("CORE_INSUFFICIENT_FUNDS",
                    "Core reports insufficient funds on account " + accountId, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String ref = "SIMHOLD-" + UUID.randomUUID().toString().substring(0, 8);
        holds.put(ref, new String[]{accountId, amount.toPlainString()});
        heldByAccount.merge(accountId, amount, BigDecimal::add);
        return ref;
    }

    @Override
    public synchronized void releaseHold(String accountId, String holdRef) {
        String[] h = holds.remove(holdRef);
        if (h == null) return; // already released or never existed: releasing is idempotent
        heldByAccount.merge(h[0], new BigDecimal(h[1]).negate(), BigDecimal::add);
    }

    @Override
    public synchronized String withdraw(String accountId, BigDecimal amount, String reference) {
        balances.merge(accountId, amount.negate(), BigDecimal::add);
        return "SIMTX-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public synchronized String deposit(String accountId, BigDecimal amount, String reference) {
        balances.merge(accountId, amount, BigDecimal::add);
        return "SIMTX-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
