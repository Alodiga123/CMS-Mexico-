package bank.cardissuing.card.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardType;
import bank.cardissuing.card.domain.PaymentType;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.funds.core.CoreBankingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Gives a debit-with-core card its account in the core at issuance.
 *
 * <p>Either links an existing, active core account the caller names, or opens one:
 * the customer's client in the core is found by our external id or created, then a
 * savings account is opened, approved and activated, and the initial deposit -- if
 * any -- goes into it. For these cards the internal ledger holds no money.
 *
 * <p>Runs <em>before</em> the card is saved: if the core says no, no half-linked card
 * is left behind. The one thing that can be left behind is a core account created
 * right before a local failure; it is logged with both ids for reconciliation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoreAccountLinker {

    private final CoreBankingClient core;
    private final CustomerRepository customers;
    private final AuditService audit;

    public boolean isCoreBacked(CardProduct product) {
        return product != null
                && product.getCardType() == CardType.DEBIT
                && product.getPaymentType() == PaymentType.POSTPAID;
    }

    /**
     * Sets {@code card.externalAccountId}. Returns false (and does nothing) when the
     * product does not keep its money in the core.
     */
    public boolean link(Card card, Customer customer, String requestedAccountId, BigDecimal initialDeposit) {
        if (!isCoreBacked(card.getProduct())) return false;

        String accountId;
        if (requestedAccountId != null && !requestedAccountId.isBlank()) {
            accountId = requestedAccountId.trim();
            if (!core.accountIsActive(accountId)) {
                throw new BusinessException("CORE_ACCOUNT_NOT_ACTIVE",
                        "Core account " + accountId + " does not exist or is not active", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else {
            String clientId = ensureClient(customer);
            accountId = core.openSavingsAccount(clientId,
                    "CMS-CARD-" + customer.getId() + "-" + card.getLast4() + "-" + System.currentTimeMillis());
        }

        if (initialDeposit != null && initialDeposit.signum() > 0) {
            core.deposit(accountId, initialDeposit, "Initial deposit, card ****" + card.getLast4());
        }

        card.setExternalAccountId(accountId);
        audit.log("LINK_CORE_ACCOUNT", "CoreAccount", accountId, "SYSTEM");
        log.info("Card ****{} of customer {} linked to core account {} (deposit {})",
                card.getLast4(), customer.getId(), accountId, initialDeposit);
        return true;
    }

    /** The customer's client id in the core, creating the client the first time. Idempotent by external id. */
    String ensureClient(Customer customer) {
        if (customer.getExternalClientId() != null && !customer.getExternalClientId().isBlank()) {
            return customer.getExternalClientId();
        }
        String externalId = "CMS-CUST-" + customer.getId();
        String clientId = core.findClientByExternalId(externalId).orElseGet(() -> {
            String[] name = splitName(customer.getFullName());
            return core.createClient(name[0], name[1], externalId);
        });
        customer.setExternalClientId(clientId);
        customers.save(customer);
        return clientId;
    }

    /** "Ana María Pérez López" -> first "Ana María", last "Pérez López"; a single word is both. */
    static String[] splitName(String fullName) {
        String n = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) return new String[]{"Cliente", "CMS"};
        String[] parts = n.split(" ");
        if (parts.length == 1) return new String[]{parts[0], parts[0]};
        int half = parts.length / 2;
        return new String[]{String.join(" ", java.util.Arrays.copyOfRange(parts, 0, half)),
                            String.join(" ", java.util.Arrays.copyOfRange(parts, half, parts.length))};
    }
}
