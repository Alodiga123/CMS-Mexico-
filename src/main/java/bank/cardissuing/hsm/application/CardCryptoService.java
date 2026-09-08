package bank.cardissuing.hsm.application;

import bank.cardissuing.card.application.PanVault;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.hsm.host.HsmHostSettings;
import bank.cardissuing.hsm.host.PayShieldHostClient;
import bank.cardissuing.hsm.host.PayShieldHostClient.Reply;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything cryptographic about a card goes through the HSM; the CMS never sees a
 * clear key and never stores a PIN or a CVV.
 *
 * At issuance: a PIN is chosen, encrypted under the LMK (BA), turned into a PVV (DG) that
 * is stored on the card; CVV, CVV2 and iCVV are computed (CW) for the personalisation
 * file and never kept. At authorization: the acquirer's PIN block is verified against
 * the PVV (EA), the track's CVV or the e-commerce CVV2 recomputed and compared (CY), the
 * chip's ARQC recomputed and answered with an ARPC (KQ).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardCryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

    private final PayShieldHostClient hsm;
    private final HsmHostSettings settings;
    private final PanVault vault;

    /** Test benches only: PINs handed out at issuance, kept in memory while the process lives. */
    private final Map<Long, String> testPins = new ConcurrentHashMap<>();

    public record Provisioned(String pvv, String pvki, String pin) { }
    public record Cvvs(String cvv, String cvv2, String icvv) { }
    public record ArqcCheck(boolean ok, String arpc) { }

    // ------------------------------------------------------------ issuance

    /** Choose a PIN, derive its PVV through the HSM. The PIN leaves here once (for the mailer) and is not stored. */
    public Provisioned provisionPin(String pan) {
        String pin = String.format("%04d", RANDOM.nextInt(10000));
        String pan12 = pan12(pan);
        Reply ba = hsm.send("BA", String.format("%02d", pin.length()) + pin + pan12);
        require(ba, "BA");
        Reply dg = hsm.send("DG", settings.getKeys().getPvk() + ba.fields() + pan12 + settings.getKeys().getPvki());
        require(dg, "DG");
        return new Provisioned(dg.fields().trim(), settings.getKeys().getPvki(), pin);
    }

    /** CVV (track, service code of the card), CVV2 (service 000) and iCVV (service 999). */
    public Cvvs cvvs(String pan, LocalDate expiry, String serviceCode) {
        String exp = expiry.format(YYMM);
        return new Cvvs(cvv(pan, exp, serviceCode), cvv(pan, exp, "000"), cvv(pan, exp, "999"));
    }

    private String cvv(String pan, String expYYMM, String service) {
        Reply r = hsm.send("CW", settings.getKeys().getCvkA() + settings.getKeys().getCvkB() + String.format("%02d", pan.length()) + pan + expYYMM + service + "0");
        require(r, "CW");
        return r.fields().trim();
    }

    // ------------------------------------------------------------ authorization

    /** Is this PIN block (under the acquirer's ZPK) the cardholder's PIN? */
    public boolean verifyPin(Card card, String pan, String pinBlockHex, String pinFormat, String acquirerId) {
        if (card.getPvv() == null) { log.warn("Card {} has no PVV: PIN cannot be verified", card.getId()); return false; }
        String zpk = settings.zpkFor(acquirerId);
        Reply r = hsm.send("EA", zpk + pinBlockHex.toUpperCase() + (pinFormat == null ? "00" : pinFormat) + pan12(pan)
                + settings.getKeys().getPvk() + (card.getPvki() != null ? card.getPvki() : settings.getKeys().getPvki()) + card.getPvv());
        if (!r.ok() && !"01".equals(r.errorCode())) throw hsmError(r, "EA");
        return r.ok();
    }

    /** Does the CVV on the track (or the CVV2 typed online, service 000) match what the CVK says? */
    public boolean verifyCvv(String pan, LocalDate expiry, String serviceCode, String cvv) {
        if (cvv == null || cvv.length() != 3) return false;
        Reply r = hsm.send("CY", settings.getKeys().getCvkA() + settings.getKeys().getCvkB() + String.format("%02d", pan.length()) + pan
                + expiry.format(YYMM) + serviceCode + "0" + cvv);
        if (!r.ok() && !"39".equals(r.errorCode()) && !"01".equals(r.errorCode())) throw hsmError(r, "CY");
        return r.ok();
    }

    /** Is the chip's ARQC genuine? On success the ARPC to send back in tag 91. */
    public ArqcCheck verifyArqc(String pan, String psn, String atcHex, String transactionDataHex, String arqcHex, String arcHex) {
        Reply r = hsm.send("KQ", settings.getKeys().getImk() + String.format("%02d", pan.length()) + pan + (psn == null ? "00" : psn) + atcHex.toUpperCase()
                + String.format("%03d", transactionDataHex.length()) + transactionDataHex.toUpperCase() + arqcHex.toUpperCase() + arcHex.toUpperCase());
        if (r.ok()) return new ArqcCheck(true, r.fields().trim());
        if ("01".equals(r.errorCode())) return new ArqcCheck(false, null);
        throw hsmError(r, "KQ");
    }

    public boolean hsmUp() { return hsm.isUp(); }

    // ------------------------------------------------------------ test benches

    public void rememberTestPin(Long cardId, String pin) { if (settings.isExposeTestSecrets()) testPins.put(cardId, pin); }
    public Optional<String> testPin(Long cardId) { return Optional.ofNullable(testPins.get(cardId)); }
    public boolean exposesTestSecrets() { return settings.isExposeTestSecrets(); }

    /** The PAN of a card, from the vault. */
    public Optional<String> panOf(Card card) {
        return card.getPanEncrypted() == null ? Optional.empty() : Optional.of(vault.decrypt(card.getPanEncrypted()));
    }

    // ------------------------------------------------------------ helpers

    /** The 12 digits the PIN block is bound to: the rightmost 12 excluding the check digit. */
    public static String pan12(String pan) {
        String d = pan.replaceAll("\\D", "");
        return d.substring(d.length() - 13, d.length() - 1);
    }

    private static void require(Reply r, String cmd) { if (!r.ok()) throw hsmError(r, cmd); }

    private static BusinessException hsmError(Reply r, String cmd) {
        return new BusinessException("HSM_COMMAND_FAILED", "HSM " + cmd + " answered error " + r.errorCode(), HttpStatus.SERVICE_UNAVAILABLE);
    }
}
