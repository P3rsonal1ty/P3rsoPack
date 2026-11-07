package dev.atelier.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Встроенный HTTP-сервер для раздачи ресурспака. Отдаёт текущий пак по {@code /<sha1>.zip}
 * (хеш в пути — клиент и CDN не закэшируют старую версию) и по {@code /pack.zip}.
 */
public final class PackHttpServer {
    private final Logger log;
    private HttpServer server;
    private ExecutorService executor;
    private volatile byte[] pack;
    private volatile String sha1;

    public PackHttpServer(Logger log) { this.log = log; }

    public synchronized void start(String bind, int port) throws IOException {
        stop();
        server = HttpServer.create(new InetSocketAddress(bind, port), 32);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        log.info("Ресурспак раздаётся по HTTP на " + bind + ":" + port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean running() { return server != null; }

    public void setPack(byte[] data, String hash) {
        this.pack = data;
        this.sha1 = hash;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            byte[] data = pack;
            String hash = sha1;
            boolean head = method.equals("HEAD");
            if (!method.equals("GET") && !head) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            if (data == null || !(path.equals("/pack.zip") || path.equals("/" + hash + ".zip"))) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("ETag", "\"" + hash + "\"");
            ex.getResponseHeaders().set("Cache-Control", path.equals("/pack.zip") ? "no-cache" : "public, max-age=31536000, immutable");
            if (head) {
                ex.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        } catch (IOException e) {
            // клиент оборвал соединение — не страшно
        }
    }
}
