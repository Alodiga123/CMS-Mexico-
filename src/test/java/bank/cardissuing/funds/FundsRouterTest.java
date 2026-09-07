package bank.cardissuing.funds;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.funds.application.CoreDebitFunds;
import bank.cardissuing.funds.application.CreditLineFunds;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.application.PrepaidLedgerFunds;
import bank.cardissuing.funds.core.SimulatedCoreBankingClient;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.ledger.application.LedgerService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FundsRouterTest {

    private final LedgerService ledger = mock(LedgerService.class);
    private final AuthorizationHoldRepository holds = mock(AuthorizationHoldRepository.class);

    private final FundsPort prepaid = new PrepaidLedgerFunds(ledger, holds);
    private final FundsPort coreDebit = new CoreDebitFunds(new SimulatedCoreBankingClient(new BigDecimal("1000")));
    private final FundsPort credit = new CreditLineFunds(holds);
    private final FundsRouter router = new FundsRouter(List.of(prepaid, coreDebit, credit));

    private static Card cardOf(CardType type, PaymentType payment) {
        Card c = new Card(new Customer(), "0000", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        c.setProduct(new CardProduct("P", "P", type, payment, CardNetwork.VISA, "400000", "MXN", null, true));
        return c;
    }

    @Test
    void prepaidProduct_routesToLedger() {
        assertSame(prepaid, router.forCard(cardOf(CardType.PREPAID, PaymentType.PREPAID)));
    }

    @Test
    void debitPrepaidPayment_routesToLedgerToo() {
        assertSame(prepaid, router.forCard(cardOf(CardType.DEBIT, PaymentType.PREPAID)));
    }

    @Test
    void debitWithCoreBalance_routesToCore() {
        assertSame(coreDebit, router.forCard(cardOf(CardType.DEBIT, PaymentType.POSTPAID)));
    }

    @Test
    void creditProduct_routesToCreditLine() {
        assertSame(credit, router.forCard(cardOf(CardType.CREDIT, PaymentType.POSTPAID)));
    }

    @Test
    void cardWithoutProduct_isRejected() {
        Card c = new Card(new Customer(), "0000", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        BusinessException ex = assertThrows(BusinessException.class, () -> router.forCard(c));
        assertEquals("CARD_WITHOUT_PRODUCT", ex.getErrorCode());
    }

    @Test
    void coreDebit_withoutLinkedAccount_isRejected() {
        Card c = cardOf(CardType.DEBIT, PaymentType.POSTPAID);
        BusinessException ex = assertThrows(BusinessException.class, () -> coreDebit.available(c));
        assertEquals("CARD_NOT_LINKED_TO_CORE", ex.getErrorCode());
    }

    @Test
    void coreDebit_withLinkedAccount_readsSimulatedBalance() {
        Card c = cardOf(CardType.DEBIT, PaymentType.POSTPAID);
        c.setExternalAccountId("SAV-42");
        assertEquals(new BigDecimal("1000"), coreDebit.available(c));
    }
}
