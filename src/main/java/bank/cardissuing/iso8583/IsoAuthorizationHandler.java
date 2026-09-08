package bank.cardissuing.iso8583;

import bank.cardissuing.card.application.PanVault;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import bank.cardissuing.hsm.application.CardCryptoService;
import bank.cardissuing.transaction.application.AuthorizationService;
import bank.cardissuing.transaction.domain.AuthorizationRequest;
import bank.cardissuing.transaction.domain.AuthorizationResponse;
import bank.cardissuing.transaction.domain.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the CMS does with each ISO 8583 message.
 *
 * 0800 network management: answered 0810 / 00. 0100 and 0200: the PAN finds the card (by
 * its hash), the cryptography is checked with the HSM (PIN block against the PVV, track
 * CVV or e-commerce CVV2 against the CVK, chip ARQC against the issuer master key), and
 * only then the authorizer decides; the answer carries field 39, an approval code in 38
 * and, for chip, the ARPC in 55. 0400 / 0420: the original is found by RRN or STAN and
 * released. 0120 / 0220: the network stood in for us; the operation is recorded as an
 * advice. Nothing here throws to the socket: every message gets an answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsoAuthorizationHandler {

    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final IsoSettings settings;
    private final CardRepository cards;
    private final PanVault vault;
    private final CardCryptoService crypto;
    private final AuthorizationService authorizer;
    private final AuthorizationHoldRepository holds;
    private final bank.cardissuing.fraud.application.FraudService fraudService;
    private final bank.cardissuing.funds.application.FundsRouter fundsRouter;

    /** Recent traffic for the console, masked. */
    public record Trace(LocalDateTime at, String peer, String mti, String pan, String amount, String code, String note, long millis) { }
    private final Deque<Trace> recent = new ArrayDeque<>();

    public byte[] handle(byte[] body, String peer) {
        long t0 = System.currentTimeMillis();
        Iso8583Message req;
        try {
            req = Iso8583Codec.decode(body);
        } catch (RuntimeException e) {
            log.warn("ISO 8583 from {}: unparseable message ({})", peer, e.getMessage());
            return null;
        }
        Iso8583Message resp;
        String note = "";
        try {
            if (req.isNetwork()) { resp = req.reply().set(39, "00"); if (req.has(70)) resp.set(70, req.get(70)); }
            else if (req.isReversal()) { Object[] r = reverse(req); resp = (Iso8583Message) r[0]; note = (String) r[1]; }
            else if (req.isAuthorization() && req.isAdvice()) { Object[] r = advice(req); resp = (Iso8583Message) r[0]; note = (String) r[1]; }
            else if (req.isAuthorization()) { Object[] r = authorize(req); resp = (Iso8583Message) r[0]; note = (String) r[1]; }
            else { resp = req.reply().set(39, "12"); note = "unsupported MTI"; }
        } catch (RuntimeException e) {
            log.error("ISO 8583 {} from {}: {}", req.getMti(), peer, e.toString());
            resp = req.reply().set(39, ResponseCode.SYSTEM_ERROR.getCode());
            note = "error: " + e.getMessage();
        }
        long ms = System.currentTimeMillis() - t0;
        remember(new Trace(LocalDateTime.now(), peer, req.getMti(), req.has(2) ? PanVault.mask(req.get(2)) : null, req.get(4), resp.get(39), note, ms));
        log.info("ISO {} -> {} 39={} {} ({} ms) {}", req.getMti(), resp.getMti(), resp.get(39), req.has(2) ? PanVault.mask(req.get(2)) : "", ms, note);
        return Iso8583Codec.encode(resp);
    }

    // ------------------------------------------------------------ authorization

    private Object[] authorize(Iso8583Message req) {
        Iso8583Message resp = req.reply();
        if (req.has(3) && req.get(3).startsWith("20")) return refund(req);
        String pan = req.get(2);
        if (pan == null && req.has(35)) pan = req.get(35).split("[=D]")[0];
        if (pan == null || !pan.matches("\\d{12,19}")) return new Object[] {resp.set(39, ResponseCode.INVALID_CARD.getCode()), "no PAN"};
        Optional<Card> found = cards.findByPanHash(vault.hash(pan));
        if (found.isEmpty()) return new Object[] {resp.set(39, ResponseCode.INVALID_CARD.getCode()), "unknown PAN"};
        Card card = found.get();

        // expiry on the message must match the card's
        if (req.has(14) && card.getExpiryDate() != null && !req.get(14).equals(card.getExpiryDate().format(YYMM))) {
            return new Object[] {resp.set(39, ResponseCode.EXPIRED_CARD.getCode()), "expiry mismatch"};
        }

        // --- cryptography, before any business rule ---
        String arpc = null;
        try {
            if (req.has(52)) {
                if (!crypto.verifyPin(card, pan, req.get(52), "00", req.get(32))) return cryptoDecline(req, card, resp, ResponseCode.INCORRECT_PIN, "PIN");
            }
            if (settings.isVerifyCvv() && req.has(35)) {
                Track2 t = Track2.parse(req.get(35));
                if (t != null && t.cvv() != null && !crypto.verifyCvv(pan, t.expiry(), t.serviceCode(), t.cvv())) {
                    return cryptoDecline(req, card, resp, ResponseCode.DO_NOT_HONOR, "CVV");
                }
            }
            String cvv2 = privateData(req, "CVV2");
            if (settings.isVerifyCvv() && cvv2 != null && card.getExpiryDate() != null) {
                if (!crypto.verifyCvv(pan, card.getExpiryDate(), "000", cvv2)) return cryptoDecline(req, card, resp, ResponseCode.CVV2_MISMATCH, "CVV2");
            }
            if (settings.isVerifyArqc() && req.has(55)) {
                Map<String, String> tlv = Tlv.parse(req.get(55));
                String arqc = tlv.get("9F26");
                if (arqc != null) {
                    String psn = tlv.getOrDefault("5F34", card.getPanSequence() != null ? card.getPanSequence() : "00");
                    if (psn.length() == 2 && !psn.matches("\\d\\d")) psn = "00";
                    String atc = tlv.getOrDefault("9F36", "0000");
                    String data = Tlv.cdolData(tlv);
                    CardCryptoService.ArqcCheck c = crypto.verifyArqc(pan, psn, atc, data, arqc, settings.getArc());
                    if (!c.ok()) return cryptoDecline(req, card, resp, ResponseCode.DO_NOT_HONOR, "ARQC");
                    arpc = c.arpc();
                }
            }
        } catch (BusinessException e) {
            if (settings.isRequireHsm()) return new Object[] {resp.set(39, ResponseCode.ISSUER_UNAVAILABLE.getCode()), "HSM: " + e.getErrorCode()};
            log.warn("HSM unavailable, crypto checks skipped for card {} ({})", card.getId(), e.getErrorCode());
        }

        // --- the business decision ---
        AuthorizationRequest r = toRequest(req, card);
        String idem = "ISO:" + req.get(7) + ":" + req.get(11) + ":" + (req.has(41) ? req.get(41) : "") + ":" + (req.has(32) ? req.get(32) : "");
        AuthorizationResponse a = authorizer.authorize(r, idem);
        resp.set(39, a.getResponseCode());
        if (a.isApproved() && a.getApprovalCode() != null) resp.set(38, approvalId(a.getApprovalCode()));
        if (arpc != null) resp.set(55, Tlv.build("91", arpc + settings.getArc()));
        String note = a.isApproved() ? "approved" : a.getMessage();
        if (a.isApproved() && a.getHoldStatus() != null && a.getHoldStatus().contains("STAND_IN")) note = "approved in stand-in";
        return new Object[] {resp, note};
    }

    private AuthorizationRequest toRequest(Iso8583Message req, Card card) {
        BigDecimal amount = new BigDecimal(req.get(4)).movePointLeft(minorUnits(req.get(49)));
        String pc = req.has(3) ? req.get(3) : "000000";
        String entry = req.has(22) ? req.get(22) : "";
        String type;
        String channel;
        if (pc.startsWith("01")) { type = "ATM_WITHDRAWAL"; channel = "ATM"; }
        else if (entry.startsWith("01") || entry.startsWith("81") || "59".equals(req.get(25))) { type = "ECOMMERCE"; channel = "ECOMMERCE"; }
        else if (entry.startsWith("07") || entry.startsWith("91")) { type = "PURCHASE"; channel = "CONTACTLESS"; }
        else { type = "PURCHASE"; channel = "POS"; }
        String country = null;
        if (req.has(43) && req.get(43).length() >= 40) country = req.get(43).substring(37).trim();
        if ((country == null || country.isBlank()) && req.has(19)) country = req.get(19);
        String merchantName = req.has(43) ? req.get(43).substring(0, Math.min(37, req.get(43).length())).trim() : "ISO " + req.get(42);
        return AuthorizationRequest.builder()
                .cardId(card.getId()).amount(amount)
                .merchantName(merchantName.isBlank() ? "Comercio" : merchantName)
                .merchantId(req.has(42) ? req.get(42).trim() : null)
                .transactionType(type).channel(channel).countryCode(country != null && !country.isBlank() ? country : null)
                .rrn(req.get(37)).stan(req.get(11)).acquirerId(req.get(32))
                .build();
    }

    // ------------------------------------------------------------ reversal and advice

    /**
     * 0200 with processing code 20xxxx: a refund (credit to the cardholder) from an acquirer.
     * The card comes from the PAN when the message carries it whole, or from the original sale
     * by RRN (the way our own acquirer sends it, since it never keeps the PAN in clear). The
     * money goes back through the same funds port the card lives on: ledger, core or line.
     */
    private Object[] refund(Iso8583Message req) {
        Iso8583Message resp = req.reply();
        String pan = req.get(2);
        Optional<Card> found = Optional.empty();
        Optional<AuthorizationHold> original = Optional.empty();
        if (pan != null && pan.matches("\\d{12,19}")) found = cards.findByPanHash(vault.hash(pan));
        if (found.isEmpty() && req.has(37)) {
            original = holds.findFirstByRrnOrderByCreatedAtDesc(req.get(37));
            found = original.map(h -> h.getCard().getId()).flatMap(cards::findById);   // the hold's card is a lazy proxy; load it here, outside any session
        }
        if (found.isEmpty()) return new Object[] {resp.set(39, ResponseCode.UNABLE_TO_LOCATE.getCode()), "refund: original not found"};
        Card card = found.get();
        BigDecimal amount = new BigDecimal(req.get(4)).movePointLeft(minorUnits(req.get(49)));
        if (amount.signum() <= 0) return new Object[] {resp.set(39, "13"), "refund: amount"};
        if (original.isPresent() && amount.compareTo(original.get().getAmount()) > 0) {
            return new Object[] {resp.set(39, "13"), "refund above the original " + original.get().getAmount()};
        }
        String reference = "REFUND-" + (req.has(37) ? req.get(37).trim() : req.get(11));
        fundsRouter.forCard(card).credit(card, amount, reference);
        String approval = String.format("%06d", Math.abs((reference + System.nanoTime()).hashCode()) % 1_000_000);
        log.info("ISO refund {} {} to card {} ({})", amount, req.has(49) ? req.get(49) : "", card.getId(), reference);
        return new Object[] {resp.set(39, "00").set(38, approval), "refund " + amount + " -> card " + card.getId()};
    }

    private Object[] reverse(Iso8583Message req) {
        Iso8583Message resp = req.reply();
        Optional<AuthorizationHold> hold = Optional.empty();
        if (req.has(37)) hold = holds.findFirstByRrnOrderByCreatedAtDesc(req.get(37));
        if (hold.isEmpty() && req.has(90)) {
            String stan = req.get(90).substring(4, 10);
            String acquirer = req.get(90).substring(20, 31).replaceFirst("^0+(?!$)", "");
            hold = holds.findFirstByStanAndAcquirerIdAndCreatedAtAfterOrderByCreatedAtDesc(stan, acquirer, LocalDateTime.now().minusDays(7));
        }
        if (hold.isEmpty()) return new Object[] {resp.set(39, ResponseCode.UNABLE_TO_LOCATE.getCode()), "original not found"};
        try {
            AuthorizationHold h = authorizer.reverse(hold.get().getApprovalCode());
            return new Object[] {resp.set(39, "00").set(38, approvalId(h.getApprovalCode())), "reversed " + h.getApprovalCode()};
        } catch (BusinessException e) {
            if ("HOLD_INVALID_STATE".equals(e.getErrorCode())) return new Object[] {resp.set(39, "00"), "already " + hold.get().getStatus()};
            throw e;
        }
    }

    private Object[] advice(Iso8583Message req) {
        Iso8583Message resp = req.reply();
        String pan = req.get(2);
        // A completion without PAN but with the RRN of a pre-authorization we hold: capture it (partial amounts allowed).
        if ((pan == null || !pan.matches("\\d{12,19}")) && req.has(37)) {
            Optional<AuthorizationHold> pre = holds.findFirstByRrnOrderByCreatedAtDesc(req.get(37));
            if (pre.isEmpty()) return new Object[] {resp.set(39, ResponseCode.UNABLE_TO_LOCATE.getCode()), "completion: original not found"};
            AuthorizationHold h = pre.get();
            if (h.getStatus() == bank.cardissuing.funds.domain.HoldStatus.CAPTURED) return new Object[] {resp.set(39, "00").set(38, approvalId(h.getApprovalCode())), "completion: already captured"};
            BigDecimal amount = new BigDecimal(req.get(4)).movePointLeft(minorUnits(req.get(49)));
            try {
                AuthorizationHold c = authorizer.capture(h.getApprovalCode(), amount);
                return new Object[] {resp.set(39, "00").set(38, approvalId(c.getApprovalCode())), "completion captured " + amount + " of " + h.getAmount()};
            } catch (BusinessException e) {
                String code = "HOLD_INVALID_STATE".equals(e.getErrorCode()) ? ResponseCode.UNABLE_TO_LOCATE.getCode() : "13";
                return new Object[] {resp.set(39, code), "completion refused: " + e.getMessage()};
            }
        }
        Optional<Card> found = pan == null ? Optional.empty() : cards.findByPanHash(vault.hash(pan));
        if (found.isEmpty()) return new Object[] {resp.set(39, ResponseCode.INVALID_CARD.getCode()), "unknown PAN"};
        Card card = found.get();
        AuthorizationRequest r = toRequest(req, card);
        AuthorizationHold h = authorizer.recordAdvice(card, r, req.has(38) ? req.get(38) : null);
        return new Object[] {resp.set(39, "00").set(38, approvalId(h.getApprovalCode())), "advice recorded " + h.getApprovalCode()};
    }

    /** A cryptographic decline is an attempt like any other: the fraud rules and the console must see it. */
    private Object[] cryptoDecline(Iso8583Message req, Card card, Iso8583Message resp, ResponseCode code, String what) {
        try {
            AuthorizationRequest r = toRequest(req, card);
            String cardType = card.getProduct() != null ? card.getProduct().getCardType().name() : null;
            fraudService.record(card, r, AuthorizationResponse.decline(code, what + " verification failed", cardType), null);
        } catch (RuntimeException e) {
            log.warn("Could not record crypto decline for card {}: {}", card.getId(), e.getMessage());
        }
        return new Object[] {resp.set(39, code.getCode()), what};
    }

    // ------------------------------------------------------------ helpers

    /** Field 38 is six characters: the tail of our approval code. */
    public static String approvalId(String approvalCode) {
        String s = approvalCode.replace("AUTH-", "");
        return s.length() >= 6 ? s.substring(s.length() - 6) : String.format("%6s", s).replace(' ', '0');
    }

    static int minorUnits(String currency) {
        return switch (currency == null ? "" : currency) { case "952", "953", "704", "392" -> 0; default -> 2; };
    }

    /** "KEY=value;KEY2=value" pairs in field 48. */
    static String privateData(Iso8583Message req, String key) {
        if (!req.has(48)) return null;
        for (String kv : req.get(48).split(";")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).trim().equalsIgnoreCase(key)) return kv.substring(i + 1).trim();
        }
        return null;
    }

    /** Track 2: PAN = YYMM SVC PVKI PVV CVV (the simulator's discretionary layout). */
    record Track2(String pan, LocalDate expiry, String serviceCode, String pvki, String pvv, String cvv) {
        static Track2 parse(String t) {
            int sep = t.indexOf('=');
            if (sep < 0) sep = t.indexOf('D');
            if (sep < 0 || t.length() < sep + 8) return null;
            String pan = t.substring(0, sep);
            String rest = t.substring(sep + 1);
            String yymm = rest.substring(0, 4);
            String svc = rest.substring(4, 7);
            String disc = rest.substring(7);
            String pvki = disc.length() >= 1 ? disc.substring(0, 1) : null;
            String pvv = disc.length() >= 5 ? disc.substring(1, 5) : null;
            String cvv = disc.length() >= 8 ? disc.substring(5, 8) : null;
            LocalDate exp = LocalDate.of(2000 + Integer.parseInt(yymm.substring(0, 2)), Integer.parseInt(yymm.substring(2, 4)), 1);
            return new Track2(pan, exp, svc, pvki, pvv, cvv);
        }
    }

    /** Minimal BER-TLV over hex text, enough for field 55 (one-byte lengths, one- or two-byte tags). */
    static final class Tlv {
        static Map<String, String> parse(String hex) {
            Map<String, String> out = new LinkedHashMap<>();
            String h = hex.toUpperCase();
            int i = 0;
            while (i + 4 <= h.length()) {
                String tag = h.substring(i, i + 2); i += 2;
                if ((Integer.parseInt(tag, 16) & 0x1F) == 0x1F) { tag += h.substring(i, i + 2); i += 2; }
                int len = Integer.parseInt(h.substring(i, i + 2), 16); i += 2;
                if (len >= 0x80) { int n = len & 0x7F; len = Integer.parseInt(h.substring(i, i + 2 * n), 16); i += 2 * n; }
                if (i + 2 * len > h.length()) break;
                out.put(tag, h.substring(i, i + 2 * len)); i += 2 * len;
            }
            return out;
        }

        static String build(String tag, String valueHex) {
            return tag + String.format("%02X", valueHex.length() / 2) + valueHex.toUpperCase();
        }

        /** The data the card signed, in CDOL1 order: 9F02 9F03 9F1A 95 5F2A 9A 9C 9F37 82 9F36 9F10. */
        static String cdolData(Map<String, String> t) {
            List<String> order = List.of("9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82", "9F36", "9F10");
            StringBuilder sb = new StringBuilder();
            for (String tag : order) sb.append(t.getOrDefault(tag, ""));
            return sb.toString();
        }
    }

    private void remember(Trace t) {
        synchronized (recent) {
            recent.addFirst(t);
            while (recent.size() > settings.getLogSize()) recent.removeLast();
        }
    }

    public List<Trace> recent() { synchronized (recent) { return new ArrayList<>(recent); } }
}
