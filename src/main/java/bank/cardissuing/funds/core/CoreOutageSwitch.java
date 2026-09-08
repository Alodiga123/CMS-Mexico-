package bank.cardissuing.funds.core;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * A breaker in front of the core, for two reasons: a real outage is remembered for a few
 * seconds so a flood of authorizations does not each wait on a dead socket (they go
 * straight to stand-in), and a test bench can pull the switch to rehearse stand-in
 * without touching Mifos.
 */
@Component
public class CoreOutageSwitch {

    /** Whether the API may force an outage (test benches). */
    @Value("${core.allow-simulated-outage:false}")
    private boolean allowSimulated;

    /** Seconds a detected outage is trusted before the core is tried again. */
    @Value("${core.outage-memory-seconds:15}")
    private int memorySeconds;

    private volatile boolean forcedDown;
    private volatile LocalDateTime openUntil;

    public boolean isDown() { return forcedDown || (openUntil != null && LocalDateTime.now().isBefore(openUntil)); }

    public boolean isForced() { return forcedDown; }

    /** Called by the core clients when the core does not answer. */
    public void reportUnreachable() { openUntil = LocalDateTime.now().plusSeconds(memorySeconds); }

    /** Called when a core call succeeds again. */
    public void reportUp() { openUntil = null; }

    public void force(boolean down) {
        if (!allowSimulated) throw new BusinessException("CORE_OUTAGE_NOT_ALLOWED", "core.allow-simulated-outage is off", HttpStatus.CONFLICT);
        forcedDown = down;
        if (!down) openUntil = null;
    }

    /** Throws the same CORE_UNAVAILABLE a dead core would, when the breaker is open. */
    public void guard() {
        if (isDown()) throw new BusinessException("CORE_UNAVAILABLE", forcedDown ? "core outage simulated" : "core recently unreachable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
