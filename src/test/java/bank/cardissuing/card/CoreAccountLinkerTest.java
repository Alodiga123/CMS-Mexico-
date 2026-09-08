package bank.cardissuing.card;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.application.CoreAccountLinker;
import bank.cardissuing.card.domain.*;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.customer.infrastructure.CustomerRepository;
import bank.cardissuing.funds.core.CoreBankingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoreAccountLinkerTest {

    @Mock CoreBankingClient core;
    @Mock CustomerRepository customers;
    @Mock AuditService audit;

    CoreAccountLinker linker;
    Customer customer;

    @BeforeEach
    void setUp() {
        linker = new CoreAccountLinker(core, customers, audit);
        customer = new Customer();
        customer.setId(42L);
        customer.setFullName("Ana María Pérez López");
    }

    private Card cardOn(CardType type, PaymentType payment) {
        Card c = new Card(customer, "9001", CardStatus.ACTIVE, LocalDate.now().plusYears(3));
        c.setProduct(new CardProduct("P", "P", type, payment, CardNetwork.VISA, "453212", "USD", null, true));
        return c;
    }

    @Test
    void prepaidCard_isNotLinked() {
        Card c = cardOn(CardType.PREPAID, PaymentType.PREPAID);
        assertFalse(linker.link(c, customer, null, new BigDecimal("100")));
        assertNull(c.getExternalAccountId());
        verifyNoInteractions(core);
    }

    @Test
    void coreDebit_opensAccount_creatingTheClientTheFirstTime() {
        Card c = cardOn(CardType.DEBIT, PaymentType.POSTPAID);
        when(core.findClientByExternalId("CMS-CUST-42")).thenReturn(Optional.empty());
        when(core.createClient("Ana María", "Pérez López", "CMS-CUST-42")).thenReturn("764");
        when(core.openSavingsAccount(eq("764"), startsWith("CMS-CARD-42-9001-"))).thenReturn("1432");

        assertTrue(linker.link(c, customer, null, new BigDecimal("10")));

        assertEquals("1432", c.getExternalAccountId());
        assertEquals("764", customer.getExternalClientId());
        verify(customers).save(customer);
        verify(core).deposit("1432", new BigDecimal("10"), "Initial deposit, card ****9001");
        verify(audit).log("LINK_CORE_ACCOUNT", "CoreAccount", "1432", "SYSTEM");
    }

    @Test
    void coreDebit_reusesClientFoundByExternalId() {
        Card c = cardOn(CardType.DEBIT, PaymentType.POSTPAID);
        when(core.findClientByExternalId("CMS-CUST-42")).thenReturn(Optional.of("700"));
        when(core.openSavingsAccount(eq("700"), anyString())).thenReturn("1500");

        linker.link(c, customer, null, null);

        verify(core, never()).createClient(any(), any(), any());
        verify(core, never()).deposit(any(), any(), any());
        assertEquals("700", customer.getExternalClientId());
    }

    @Test
    void coreDebit_reusesCachedClientWithoutAskingTheCore() {
        customer.setExternalClientId("764");
        Card c = cardOn(CardType.DEBIT, PaymentType.POSTPAID);
        when(core.openSavingsAccount(eq("764"), anyString())).thenReturn("1600");

        linker.link(c, customer, null, null);

        verify(core, never()).findClientByExternalId(any());
        verify(customers, never()).save(any());
    }

    @Test
    void existingAccount_isValidatedAndLinked_withoutOpeningOne() {
        Card c = cardOn(CardType.DEBIT, PaymentType.POSTPAID);
        when(core.accountIsActive("1432")).thenReturn(true);

        assertTrue(linker.link(c, customer, " 1432 ", null));

        assertEquals("1432", c.getExternalAccountId());
        verify(core, never()).openSavingsAccount(any(), any());
        verify(core, never()).createClient(any(), any(), any());
    }

    @Test
    void existingAccount_notActive_isRejected_andCardStaysUnlinked() {
        Card c = cardOn(CardType.DEBIT, PaymentType.POSTPAID);
        when(core.accountIsActive("999")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> linker.link(c, customer, "999", null));
        assertEquals("CORE_ACCOUNT_NOT_ACTIVE", ex.getErrorCode());
        assertNull(c.getExternalAccountId());
    }

    @Test
    void splitName_handlesTheUsualShapes() {
        assertArrayEquals(new String[]{"Ana María", "Pérez López"}, CoreAccountLinker.splitName("Ana María Pérez López"));
        assertArrayEquals(new String[]{"Ana", "Pérez"}, CoreAccountLinker.splitName("  Ana   Pérez "));
        assertArrayEquals(new String[]{"Madonna", "Madonna"}, CoreAccountLinker.splitName("Madonna"));
        assertArrayEquals(new String[]{"Cliente", "CMS"}, CoreAccountLinker.splitName(null));
    }
}
