package bank.cardissuing.transaction;

import bank.cardissuing.card.application.CardControlsService;
import bank.cardissuing.card.domain.*;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.transaction.application.Breach;
import bank.cardissuing.transaction.application.ControlsPolicy;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlsPolicyTest {

    @Mock CardControlsService controlsService;

    ControlsPolicy policy;
    Card card;
    CardControls controls;

    @BeforeEach
    void setUp() {
        policy = new ControlsPolicy(controlsService);
        card = new Card(new Customer(), "1111", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        CardProduct product = new CardProduct("P", "P", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "400000", "MXN", null, true);
        product.setCountry("MX");
        card.setProduct(product);
        controls = new CardControls(card);
        when(controlsService.forCard(card)).thenReturn(controls);
    }

    private AuthorizationRequest req(String amount, String channel, String country) {
        return AuthorizationRequest.builder().cardId(1L).amount(new BigDecimal(amount)).merchantName("M")
                .channel(channel).countryCode(country).build();
    }

    @Test
    void defaults_allowEverything() {
        assertTrue(policy.check(card, req("100", "POS", null)).isEmpty());
        assertTrue(policy.check(card, req("100", "ECOMMERCE", "US")).isEmpty());
    }

    @Test
    void disabledChannel_answers57() {
        controls.setEcommerceEnabled(false);
        Optional<Breach> b = policy.check(card, req("10", "ECOMMERCE", null));
        assertTrue(b.isPresent());
        assertEquals("57", b.get().code().getCode());
    }

    @Test
    void channelDerivedFromTransactionType_whenAbsent() {
        controls.setAtmEnabled(false);
        AuthorizationRequest r = AuthorizationRequest.builder().cardId(1L).amount(BigDecimal.TEN).merchantName("M")
                .transactionType("ATM_WITHDRAWAL").build();
        assertEquals("57", policy.check(card, r).orElseThrow().code().getCode());
    }

    @Test
    void unknownChannel_isRejectedNotDefaulted() {
        assertThrows(BusinessException.class, () -> policy.check(card, req("10", "CARRIER_PIGEON", null)));
    }

    @Test
    void international_disabled_answers57_andTravelNoticeOverrides() {
        controls.setInternationalEnabled(false);
        assertEquals("57", policy.check(card, req("10", "POS", "US")).orElseThrow().code().getCode());
        assertTrue(policy.check(card, req("10", "POS", "MX")).isEmpty(), "domestic still fine");
        assertTrue(policy.check(card, req("10", "POS", "MEX")).isEmpty(), "3-letter home country is domestic too");

        controls.setTravelNoticeUntil(LocalDate.now().plusDays(3));
        assertTrue(policy.check(card, req("10", "POS", "US")).isEmpty(), "travel notice allows international");

        controls.setTravelNoticeUntil(LocalDate.now().minusDays(1));
        assertTrue(policy.check(card, req("10", "POS", "US")).isPresent(), "expired notice does not");
    }

    @Test
    void perTransactionMax_answers61() {
        controls.setPerTransactionMax(new BigDecimal("50"));
        assertTrue(policy.check(card, req("50", "POS", null)).isEmpty());
        assertEquals("61", policy.check(card, req("50.01", "POS", null)).orElseThrow().code().getCode());
    }
}
