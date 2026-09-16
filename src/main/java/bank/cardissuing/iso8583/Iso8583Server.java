package bank.cardissuing.iso8583;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The socket the switch talks to: length-prefixed (2 bytes, big-endian) ISO 8583
 * messages, one connection per switch link, many messages per connection, one answer
 * per message. Every message is handed to the handler; a malformed one gets a 0x10
 * reply with response code 30 (format error) when it can be parsed at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Iso8583Server {

    private final IsoSettings settings;
    private final IsoAuthorizationHandler handler;

    private ServerSocket server;
    private ExecutorService pool;
    private final AtomicInteger connections = new AtomicInteger();
    private volatile boolean running;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!settings.isEnabled()) { log.info("ISO 8583 listener disabled"); return; }
        try {
            server = createServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(settings.getBind(), settings.getPort()));
            pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "iso8583-conn"); t.setDaemon(true); return t; });
            running = true;
            Thread acceptor = new Thread(this::acceptLoop, "iso8583-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
            log.info("ISO 8583 listener on {}:{}", settings.getBind(), settings.getPort());
        } catch (IOException e) {
            log.error("ISO 8583 listener could not bind {}:{}: {}", settings.getBind(), settings.getPort(), e.getMessage());
        }
    }

    /** ¿El origen de la conexión está en la lista de direcciones permitidas? Lista vacía = todas. */
    private boolean peerAllowed(Socket s) {
        var list = settings.getAllowedPeers();
        if (list == null || list.isEmpty()) return true;
        if (!(s.getRemoteSocketAddress() instanceof InetSocketAddress isa) || isa.getAddress() == null) return false;
        java.net.InetAddress addr = isa.getAddress();
        for (String rule : list) { if (rule != null && matchesRule(rule.trim(), addr)) return true; }
        return false;
    }

    /** Coincidencia por IP exacta o por CIDR (ip/prefijo), IPv4/IPv6. */
    private static boolean matchesRule(String rule, java.net.InetAddress addr) {
        if (rule.isEmpty()) return false;
        try {
            if (!rule.contains("/")) return java.net.InetAddress.getByName(rule).equals(addr);
            String[] p = rule.split("/", 2);
            byte[] a = addr.getAddress();
            byte[] n = java.net.InetAddress.getByName(p[0]).getAddress();
            if (a.length != n.length) return false;
            int prefix = Integer.parseInt(p[1]), full = prefix / 8, rem = prefix % 8;
            for (int i = 0; i < full; i++) if (a[i] != n[i]) return false;
            if (rem > 0) { int mask = 0xFF << (8 - rem); if ((a[full] & mask) != (n[full] & mask)) return false; }
            return true;
        } catch (Exception e) { return false; }
    }

    /** Socket TCP plano, o TLS (SSLServerSocket) cuando iso.tls.enabled=true, para cifrar el canal. */
    private ServerSocket createServerSocket() throws IOException {
        IsoSettings.Tls tls = settings.getTls();
        if (!tls.isEnabled()) return new ServerSocket();
        try {
            char[] pw = tls.getKeystorePassword().toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(tls.getKeystore())) { ks.load(fis, pw); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            TrustManager[] tms = null;
            if (tls.isNeedClientAuth()) {
                KeyStore ts = KeyStore.getInstance("PKCS12");
                try (FileInputStream tf = new FileInputStream(tls.getTruststore())) { ts.load(tf, tls.getTruststorePassword().toCharArray()); }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
                tms = tmf.getTrustManagers();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), tms, null);
            SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            if (tls.isNeedClientAuth()) { ss.setNeedClientAuth(true); log.info("ISO 8583: TLS mutuo (exige certificado de cliente, truststore {})", tls.getTruststore()); }
            log.info("ISO 8583: TLS activado (keystore {})", tls.getKeystore());
            return ss;
        } catch (Exception e) {
            throw new IOException("ISO 8583: no se pudo iniciar TLS: " + e.getMessage(), e);
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (!peerAllowed(s)) { log.warn("ISO 8583: conexión rechazada por allowlist desde {}", s.getRemoteSocketAddress()); s.close(); continue; }
                if (connections.get() >= settings.getMaxConnections()) { log.warn("ISO 8583: connection limit reached, refusing {}", s.getRemoteSocketAddress()); s.close(); continue; }
                connections.incrementAndGet();
                pool.submit(() -> serve(s));
            } catch (IOException e) {
                if (running) log.warn("ISO 8583 accept failed: {}", e.getMessage());
            }
        }
    }

    private void serve(Socket s) {
        String peer = String.valueOf(s.getRemoteSocketAddress());
        log.info("ISO 8583: link up from {}", peer);
        try (s) {
            s.setSoTimeout(settings.getIdleTimeoutMs());
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            while (running) {
                int len;
                try { len = in.readUnsignedShort(); } catch (IOException eof) { break; }
                if (len <= 0 || len > 9999) { log.warn("ISO 8583: bad length {} from {}", len, peer); break; }
                byte[] body = new byte[len];
                in.readFully(body);
                byte[] reply = handler.handle(body, peer);
                if (reply != null) {
                    synchronized (out) { out.writeShort(reply.length); out.write(reply); out.flush(); }
                }
            }
        } catch (IOException e) {
            log.info("ISO 8583: link {} closed: {}", peer, e.getMessage());
        } finally {
            connections.decrementAndGet();
            log.info("ISO 8583: link down from {}", peer);
        }
    }

    public boolean isRunning() { return running && server != null && !server.isClosed(); }

    public int connections() { return connections.get(); }

    @PreDestroy
    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }
}
