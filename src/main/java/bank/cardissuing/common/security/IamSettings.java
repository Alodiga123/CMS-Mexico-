package bank.cardissuing.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** How the console and the API authenticate: the corporate IAM for people, an API key for systems. */
@Component
@ConfigurationProperties(prefix = "security")
@Getter
@Setter
public class IamSettings {

    /** false = everything open (local development only; logged loudly at start). */
    private boolean enabled = true;
    private Iam iam = new Iam();
    private ApiKey apiKey = new ApiKey();

    @Getter @Setter
    public static class Iam {
        private String baseUrl = "http://localhost:4003/api/v1";
        /** The project code this CMS is registered under at the IAM; users need access to it. */
        private String projectCode = "EMISION_CMS";
        /** Seconds an introspection answer is reused for the same token before asking the IAM again. */
        private int cacheSeconds = 60;
        private int timeoutMs = 3000;
    }

    @Getter @Setter
    public static class ApiKey {
        /** Shared key for machine clients (scripts, the switch while there is no ISO channel). Empty = disabled. */
        private String value = "";
        /** How that client is named in the audit trail. */
        private String name = "system";
    }
}
