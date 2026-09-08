package bank.cardissuing.clearing.application;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.clearing.application.ClearingFileParser.Header;
import bank.cardissuing.clearing.application.ClearingFileParser.Line;
import bank.cardissuing.clearing.domain.ClearingRecord;
import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.infrastructure.DisputeRepository;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.HoldStatus;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.hsm.application.CardCryptoService;
import bank.cardissuing.iso8583.IsoAuthorizationHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plays the network for demos and tests: writes the clearing file an acquirer would send
 * for what our authorizer approved (every live hold of the last days), with the
 * anomalies a real cycle brings when asked: a duplicate presentment, an amount above
 * tolerance, an unknown PAN, a reversal, a chargeback on an open dispute, a fee.
 */
@Component
@RequiredArgsConstructor
public class NetworkFileSimulator {

    private final AuthorizationHoldRepository holds;
    private final DisputeRepository disputes;
    private final CardCryptoService crypto;
    private final ClearingSettings settings;

    public String build(Network network, LocalDate cycleDate, Set<String> anomalies, Long onlyCardId) {
        List<Line> lines = new ArrayList<>();
        int no = 0;
        List<AuthorizationHold> live = holds.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(HoldStatus.HELD, LocalDateTime.now().minusDays(7));
        Line first = null;
        for (AuthorizationHold h : live) {
            Card c = h.getCard();
            if (onlyCardId != null && !c.getId().equals(onlyCardId)) continue;
            String pan = crypto.panOf(c).orElse(null);
            if (pan == null) continue;   // cards issued before the vault cannot be presented by PAN
            BigDecimal fee = h.getAmount().multiply(settings.getSimulatedInterchangePercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            Line l = new Line(++no, ClearingRecord.Type.PRESENTMENT, pan, h.getRrn(), h.getStan(), IsoAuthorizationHandler.approvalId(h.getApprovalCode()),
                    h.getAmount(), settings.getCurrency(), fee, "5411", h.getMerchantId(), h.getMerchantName(), h.getCreatedAt().toLocalDate(), null);
            lines.add(l);
            if (first == null) first = l;
        }
        Line last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
        if (first != null && anomalies.contains("over")) {
            // the acquirer presents the first authorization for half again as much (a tip beyond tolerance)
            lines.set(0, copy(first, first.lineNo(), first.amount().multiply(new BigDecimal("1.50")).setScale(2, RoundingMode.HALF_UP)));
        }
        if (last != null && anomalies.contains("duplicate")) lines.add(copy(last, ++no, last.amount()));
        if (anomalies.contains("unknown")) lines.add(new Line(++no, ClearingRecord.Type.PRESENTMENT, "4532120000000000", "000000000000", "000000", "000000", new BigDecimal("9.99"), settings.getCurrency(), BigDecimal.ZERO, "5411", "UNKNOWN", "Comercio desconocido", cycleDate, null));
        if (last != null && anomalies.contains("reversal")) lines.add(new Line(++no, ClearingRecord.Type.REVERSAL, last.pan(), last.rrn(), last.stan(), last.approvalId(), last.amount(), settings.getCurrency(), BigDecimal.ZERO, last.mcc(), last.merchantId(), last.merchantName(), cycleDate, null));
        if (anomalies.contains("chargeback")) {
            for (Dispute d : disputes.findByStatusOrderByCreatedAtDesc(Dispute.Status.CHARGEBACK_SENT)) {
                Card c = d.getCard();
                if (onlyCardId != null && !c.getId().equals(onlyCardId)) continue;
                String pan = crypto.panOf(c).orElse(null);
                if (pan == null) continue;
                lines.add(new Line(++no, ClearingRecord.Type.CHARGEBACK, pan, d.getHold() != null ? d.getHold().getRrn() : null, null,
                        IsoAuthorizationHandler.approvalId(d.getApprovalCode()), d.getAmount(), settings.getCurrency(), BigDecimal.ZERO, "5411", null, "Contracargo", cycleDate, d.getReason().getCode()));
                break;
            }
        }
        if (anomalies.contains("fee")) lines.add(new Line(++no, ClearingRecord.Type.FEE, null, null, null, null, new BigDecimal("38.70"), settings.getCurrency(), BigDecimal.ZERO, null, null, "Cuota de red", cycleDate, "NETFEE"));
        return ClearingFileParser.write(new Header(network, cycleDate, network.name() + "-" + cycleDate + "-" + System.currentTimeMillis() % 100000), lines);
    }

    private static Line copy(Line l, int no, BigDecimal amount) {
        return new Line(no, l.type(), l.pan(), l.rrn(), l.stan(), l.approvalId(), amount, l.currency(), l.interchangeFee(), l.mcc(), l.merchantId(), l.merchantName(), l.txDate(), l.reasonCode());
    }
}
