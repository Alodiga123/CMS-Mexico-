package bank.cardissuing.plastics.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Knobs of card personalization, bound from {@code plastics.*}. */
@Component
@ConfigurationProperties(prefix = "plastics")
@Getter
@Setter
public class PlasticSettings {

    /** Default bureau name on batches. */
    private String manufacturer = "PERSO-BUREAU";

    /**
     * AES-256 key shared with the bureau, base64 of 32 bytes. The default is a
     * development key; production must set PLASTICS_MANUFACTURER_KEY.
     */
    private String manufacturerKeyBase64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** Dev only: lets GET /batches/{id}/file/preview return the plaintext. */
    private boolean exposePlaintext = false;

    /** ISO 7813 service code: 201 = chip, international, PIN. */
    private String serviceCode = "201";

    /** Chip profile stamped on every plastic until LACPI versioning exists. */
    private String defaultChipProfile = "LACPI-MX-01";

    private int renewalValidityYears = 3;
    private int renewalWindowDays = 60;
}
