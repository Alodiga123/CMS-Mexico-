package bank.cardissuing.funds;

import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.funds.core.CoreBankingClient;
import bank.cardissuing.funds.core.CoreBankingClient.CoreBalances;
import bank.cardissuing.funds.core.CoreBankingClient.CoreTransaction;
import bank.cardissuing.funds.core.CoreBankingClient.CoreTxType;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationItem;
import bank.cardissuing.funds.reconciliation.ReconciliationItem.Status;
import bank.cardissuing.funds.reconciliation.ReconciliationItem.Type;
import bank.cardissuing.funds.reconciliation.ReconciliationItemRepository;
import bank.cardissuing.funds.reconciliation.ReconciliationService;
import bank.cardissuing.funds.reconciliation.ReconciliationService.Finding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReconciliationServiceTest {

    static final String ACC = "1432";

    @Mock CoreBankingClient core;
    @Mock CardRepository cards;
    @Mock AuthorizationHoldRepository holds;
    @Mock ReconciliationItemRepository items;

    ReconciliationService service;
    Card card;
    List<AuthorizationHold> cmsHolds = new ArrayList<>();
    List<CoreTransaction> coreTxs = new ArrayList<>();
    List<ReconciliationItem> store = new ArrayList<>();
    AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        service = new ReconciliationService(core, cards, holds, items);
        card = new Card(new Customer(), "9001", CardStatus.ACTIVE, LocalDate.now().plusYears(1));
        card.setId(1L);
        card.setExternalAccountId(ACC);
        card.setProduct(new CardProduct("P", "P", CardType.DEBIT, PaymentType.POSTPAID, CardNetwork.VISA, "4", "USD", null, true));
        when(cards.findByExternalAccountId(ACC)).thenReturn(List.of(card));
        when(cards.findByExternalAccountIdIsNotNull()).thenReturn(List.of(card));
        when(holds.findByCardOrderByCreatedAtDesc(card)).thenAnswer(i -> new ArrayList<>(cmsHolds));
        when(core.transactions(ACC)).thenAnswer(i -> new ArrayList<>(coreTxs));
        // items repository backed by a list, so persist() can be exercised
        when(items.save(any())).thenAnswer(i -> {
            ReconciliationItem it = i.getArgument(0);
            if (it.getId() == null) { it.setId(ids.getAndIncrement()); store.add(it); }
            return it;
        });
        when(items.findByAccountIdAndStatus(any(), any())).thenAnswer(i ->
                store.stream().filter(x -> x.getAccountId().equals(i.getArgument(0)) && x.getStatus() == i.getArgument(1)).toList());
        when(items.findByItemKey(any())).thenAnswer(i ->
                store.stream().filter(x -> x.getItemKey().equals(i.getArgument(0))).findFirst());
    }

    private AuthorizationHold hold(String code, String amount, HoldStatus status, String externalRef, String captureRef) {
        AuthorizationHold h = new AuthorizationHold(card, code, new BigDecimal(amount), "M", null, "PURCHASE", LocalDateTime.now().plusDays(7));
        h.setExternalRef(externalRef);
        if (status == HoldStatus.CAPTURED) { h.capture(new BigDecimal(amount)); h.setCaptureRef(captureRef); }
        else if (status == HoldStatus.RELEASED) h.release();
        else if (status == HoldStatus.EXPIRED) h.expire();
        cmsHolds.add(h);
        return h;
    }

    private CoreTransaction tx(String id, CoreTxType type, String amount, String releaseRef, String note) {
        CoreTransaction t = new CoreTransaction(id, type, new BigDecimal(amount), LocalDate.now(), false, releaseRef, note);
        coreTxs.add(t);
        return t;
    }

    private void coreOnHold(String held, String available) {
        when(core.balances(ACC)).thenReturn(new CoreBalances(new BigDecimal(available).add(new BigDecimal(held)), new BigDecimal(available)));
    }

    private List<Type> types(List<Finding> f) { return f.stream().map(Finding::type).toList(); }

    @Test
    void consistentAccount_hasNoDifferences() {
        hold("AUTH-A", "30", HoldStatus.HELD, "H1", null);
        hold("AUTH-B", "25", HoldStatus.CAPTURED, "H2", "W1");
        tx("H1", CoreTxType.HOLD, "30", null, null);
        tx("H2", CoreTxType.HOLD, "25", "R2", null);
        tx("R2", CoreTxType.RELEASE, "25", null, null);
        tx("W1", CoreTxType.WITHDRAWAL, "25", null, "AUTH-B");
        coreOnHold("30", "100");

        assertTrue(service.findDifferences(ACC).isEmpty());
    }

    @Test
    void heldInCms_butReleasedInCore_isHoldMissing_andTotalsDisagree() {
        hold("AUTH-A", "30", HoldStatus.HELD, "H1", null);
        tx("H1", CoreTxType.HOLD, "30", "R1", null);
        tx("R1", CoreTxType.RELEASE, "30", null, null);
        coreOnHold("0", "100");

        List<Finding> f = service.findDifferences(ACC);
        assertEquals(List.of(Type.HOLD_MISSING_IN_CORE, Type.HOLD_TOTAL_MISMATCH), types(f));
        assertEquals("AUTH-A", f.get(0).approvalCode());
        assertEquals(new BigDecimal("30"), f.get(1).cmsAmount());
        assertEquals(0, new BigDecimal("0").compareTo(f.get(1).coreAmount()));
    }

    @Test
    void capture_matchedByNoteWhenNoCaptureRef() {
        hold("AUTH-B", "25", HoldStatus.CAPTURED, "H2", null);
        tx("H2", CoreTxType.HOLD, "25", "R2", null);
        tx("W9", CoreTxType.WITHDRAWAL, "25", null, "AUTH-B");
        coreOnHold("0", "75");

        assertTrue(service.findDifferences(ACC).isEmpty());
    }

    @Test
    void captured_butCoreStillHolds_isHoldNotReleased() {
        hold("AUTH-B", "25", HoldStatus.CAPTURED, "H2", "W1");
        tx("H2", CoreTxType.HOLD, "25", null, null);
        tx("W1", CoreTxType.WITHDRAWAL, "25", null, "AUTH-B");
        coreOnHold("25", "50");

        List<Finding> f = service.findDifferences(ACC);
        assertEquals(List.of(Type.HOLD_NOT_RELEASED, Type.HOLD_TOTAL_MISMATCH), types(f));
    }

    @Test
    void capturedWithoutAnyCoreDebit_isCaptureMissing() {
        hold("AUTH-B", "25", HoldStatus.CAPTURED, "H2", "W1");
        tx("H2", CoreTxType.HOLD, "25", "R2", null);
        coreOnHold("0", "100");

        assertEquals(List.of(Type.CAPTURE_MISSING_IN_CORE), types(service.findDifferences(ACC)));
    }

    @Test
    void coreDebitedADifferentAmount_isAmountMismatch() {
        hold("AUTH-B", "25", HoldStatus.CAPTURED, "H2", "W1");
        tx("H2", CoreTxType.HOLD, "25", "R2", null);
        tx("W1", CoreTxType.WITHDRAWAL, "27.50", null, "AUTH-B");
        coreOnHold("0", "72.50");

        List<Finding> f = service.findDifferences(ACC);
        assertEquals(List.of(Type.AMOUNT_MISMATCH), types(f));
        assertEquals(new BigDecimal("27.50"), f.get(0).coreAmount());
    }

    @Test
    void unexplainedCoreDebit_isUnknown_butOlderThanTheCardIsIgnored() {
        card.setCreatedAt(LocalDateTime.now().minusDays(1));
        tx("W7", CoreTxType.WITHDRAWAL, "40", null, "teller");
        coreTxs.add(new CoreTransaction("W0", CoreTxType.WITHDRAWAL, new BigDecimal("5"), LocalDate.now().minusDays(30), false, null, "old"));
        tx("D1", CoreTxType.DEPOSIT, "100", null, "top-up");
        coreOnHold("0", "55");

        List<Finding> f = service.findDifferences(ACC);
        assertEquals(List.of(Type.UNKNOWN_CORE_DEBIT), types(f));
        assertEquals("W7", f.get(0).coreRef());
    }

    @Test
    void persist_opensItems_thenResolvesThemWhenTheyDisappear() {
        AuthorizationHold h = hold("AUTH-A", "30", HoldStatus.HELD, "H1", null);
        tx("H1", CoreTxType.HOLD, "30", "R1", null);
        coreOnHold("0", "100");

        ReconciliationService.RunResult first = service.reconcileAccount(ACC);
        assertEquals(2, first.opened());
        assertEquals(2, store.stream().filter(i -> i.getStatus() == Status.OPEN).count());

        // same state again: nothing new, both still open
        ReconciliationService.RunResult second = service.reconcileAccount(ACC);
        assertEquals(0, second.opened());
        assertEquals(2, second.stillOpen());

        // the CMS catches up (reverses the orphan): the run resolves both
        h.release();
        coreOnHold("0", "100");
        ReconciliationService.RunResult third = service.reconcileAccount(ACC);
        assertEquals(2, third.resolved());
        assertTrue(store.stream().allMatch(i -> i.getStatus() == Status.RESOLVED));
        assertTrue(store.get(0).getResolution().startsWith("auto"));
    }
}
