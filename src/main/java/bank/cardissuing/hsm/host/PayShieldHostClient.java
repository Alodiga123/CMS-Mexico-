package bank.cardissuing.hsm.host;

import bank.cardissuing.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * The host-command channel to the HSM (payShield wire format): a 2-byte big-endian length,
 * a fixed header, the 2-character command code and its fields; the answer carries the
 * response code, a 2-character error code and the fields. One short-lived connection per
 * command keeps it simple and thread-safe; the authorizer issues at most three per operation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PayShieldHostClient {

    private final HsmHostSettings settings;

    /** What the HSM answered: "00" is success; anything else names the failure. */
    public record Reply(String responseCode, String errorCode, String fields) {
        public boolean ok() { return "00".equals(errorCode); }
    }

    public Reply send(String command, String fields) {
        String header = settings.getHeader();
        byte[] body = (header + command + fields).getBytes(StandardCharsets.US_ASCII);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(settings.getHost(), settings.getPort()), settings.getTimeoutMs());
            s.setSoTimeout(settings.getTimeoutMs());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeShort(body.length);
            out.write(body);
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            int len = in.readUnsignedShort();
            byte[] resp = new byte[len];
            in.readFully(resp);
            String text = new String(resp, StandardCharsets.US_ASCII);
            int h = header.length();
            if (text.length() < h + 4) throw new IOException("short HSM reply: " + text);
            Reply r = new Reply(text.substring(h, h + 2), text.substring(h + 2, h + 4), text.substring(h + 4));
            if (log.isDebugEnabled()) log.debug("HSM {} -> {} {}", command, r.responseCode(), r.errorCode());
            return r;
        } catch (IOException e) {
            log.warn("HSM host {}:{} unreachable for {}: {}", settings.getHost(), settings.getPort(), command, e.getMessage());
            throw new BusinessException("HSM_UNAVAILABLE", "HSM not reachable: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public boolean isUp() {
        try { return send("NC", "").ok(); } catch (BusinessException e) { return false; }
    }
}
