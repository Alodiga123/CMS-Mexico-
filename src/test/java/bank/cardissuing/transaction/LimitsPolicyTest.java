package bank.cardissuing.transaction;

import bank.cardissuing.card.application.CardControlsService;
import bank.cardissuing.card.domain.*;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.transaction.application.Breach;
import bank.cardissuing.transaction.application.LimitsPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LimitsPolicyTest {

    @Mock AuthorizationHoldRepository holds;
    @Mock CardControlsService controlsService;

    LimitsPolicy policy;
    Card card;
    CardControls controls;

    @BeforeEach
    void setUp() {
        policy = new LimitsPolicy(holds, controlsService);
        card = new Card(new Customer(), "1111", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        card.setProduct(new CardProduct("P", "P", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "400000", "MXN",
                null, new BigDecimal("1000"), new BigDecimal("5000"), new BigDecimal("20000"), "MX", true));
        controls = new CardControls(card);
        when(controlsService.forCard(card)).thenReturn(controls);
        when(holds.sumSince(eq(card), any(), any())).thenReturn(new BigDecimal("900"));
    }

    @Test
    void productLimit_applies_whenCardHasNone() {
        assertTrue(policy.check(card, new BigDecimal("100")).isEmpty(), "900 + 100 = 1000 fits");
        Optional<Breach> b = policy.check(card, new BigDecimal("101"));
        assertEquals("61", b.orElseThrow().code().getCode());
        assertTrue(b.get().detail().startsWith("daily"));
    }

    @Test
    void cardLimit_winsOverProduct() {
        controls.setDailyLimit(new BigDecimal("2000"));
        assertTrue(policy.check(card, new BigDecimal("1000")).isEmpty(), "card allows 2000/day");
    }

    @Test
    void cardLimit_canBeStricterThanProduct() {
        controls.setDailyLimit(new BigDecimal("950"));
        assertEquals("61", policy.check(card, new BigDecimal("60")).orElseThrow().code().getCode());
    }

    @Test
    void zeroLimit_meansNoLimitForThatWindow() {
        controls.setDailyLimit(BigDecimal.ZERO);
        controls.setWeeklyLimit(BigDecimal.ZERO);
        controls.setMonthlyLimit(BigDecimal.ZERO);
        assertTrue(policy.check(card, new BigDecimal("999999")).isEmpty());
    }
}
