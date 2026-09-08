package bank.cardissuing.hsm.host;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the HSM host port is and which keys (as LMK-encrypted blobs) the issuer uses.
 * The blobs are what the HSM handed back when the keys were generated under its LMK; they
 * are useless without that HSM. Defaults are the local simulator's development keys.
 */
@Component
@ConfigurationProperties(prefix = "hsm.host")
@Getter
@Setter
public class HsmHostSettings {
    private String host = "localhost";
    private int port = 1500;
    private String header = "BANK";
    private int timeoutMs = 2000;
    /** Expose PAN, PIN and CVVs of a card through the API for test benches. Never in production. */
    private boolean exposeTestSecrets = false;

    private Keys keys = new Keys();

    @Getter @Setter
    public static class Keys {
        /** PIN verification key pair (PVK), used for PVV generation and PIN verification. */
        private String pvk = "";
        private String pvki = "1";
        /** Card verification keys A and B (CVV, CVV2, iCVV). */
        private String cvkA = "";
        private String cvkB = "";
        /** Issuer master key for EMV application cryptograms (ARQC / ARPC). */
        private String imk = "";
        /** Default zone PIN key: the key the acquirer's PIN blocks arrive under when no acquirer-specific one is configured. */
        private String zpk = "";
        /** Acquirer institution id (ISO field 32) to ZPK blob, for acquirers with their own zone key. */
        private Map<String, String> acquirerZpk = new LinkedHashMap<>();
    }

    public String zpkFor(String acquirerId) {
        if (acquirerId != null && keys.acquirerZpk.containsKey(acquirerId)) return keys.acquirerZpk.get(acquirerId);
        return keys.zpk;
    }
}
