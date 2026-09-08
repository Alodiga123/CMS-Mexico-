package bank.cardissuing.iso8583;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The ISO 8583 listener the switch connects to, and the crypto policy applied to each message. */
@Component
@ConfigurationProperties(prefix = "iso")
@Getter
@Setter
public class IsoSettings {
    private boolean enabled = true;
    private int port = 8583;
    private String bind = "0.0.0.0";
    /** Milliseconds a connection may sit idle before it is closed. */
    private int idleTimeoutMs = 300_000;
    /** Most simultaneous switch connections. */
    private int maxConnections = 16;
    /** Reject a message whose crypto could not be checked because the HSM is down (fail closed). */
    private boolean requireHsm = true;
    /** Verify the track CVV when field 35 carries it. */
    private boolean verifyCvv = true;
    /** Verify the chip cryptogram when field 55 carries one. */
    private boolean verifyArqc = true;
    /** Issuer's ARC answered on a good ARQC (tag 91 = ARPC + this). */
    private String arc = "3030";
    /** Messages kept in memory for the console. */
    private int logSize = 200;
}
