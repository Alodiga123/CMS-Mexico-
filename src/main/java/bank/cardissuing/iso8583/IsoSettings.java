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
    /** Direcciones (IP exacta o CIDR) autorizadas a conectarse al listener. Vacío = todas. */
    private java.util.List<String> allowedPeers = new java.util.ArrayList<>();
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

    /** TLS para el canal 8583 (cifra el PAN en tránsito, PCI DSS 4.2). */
    private final Tls tls = new Tls();
    public Tls getTls() { return tls; }

    @Getter
    @Setter
    public static class Tls {
        private boolean enabled = false;
        /** Ruta al keystore PKCS12 con la llave y el certificado del servidor ISO. */
        private String keystore = "";
        private String keystorePassword = "";
        /** TLS mutuo: exige y valida el certificado de cliente del adquirente. */
        private boolean needClientAuth = false;
        /** Truststore PKCS12 con el certificado de cliente autorizado (el del adquirente). */
        private String truststore = "";
        private String truststorePassword = "";
    }
}
