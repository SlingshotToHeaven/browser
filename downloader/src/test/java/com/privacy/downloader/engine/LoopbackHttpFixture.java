package com.privacy.downloader.engine;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Scripted HTTP/1.1 replies, including headers real servers normally prevent us from emitting. */
final class LoopbackHttpFixture implements AutoCloseable {
    interface Reply {
        Response respond(Request request) throws Exception;
    }

    static final class Request {
        final String target;
        final String method;
        private final Map<String, String> headers = new HashMap<>();

        Request(String method, String target) {
            this.method = method;
            this.target = target;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    interface Body {
        void writeTo(OutputStream output) throws IOException;
    }

    static final class Response implements Reply {
        private final int status;
        private final String framing;
        private final Body body;
        private final String[] extraHeaders;

        private Response(int status, String framing, Body body, String[] extraHeaders) {
            this.status = status;
            this.framing = framing;
            this.body = body;
            this.extraHeaders = extraHeaders;
        }

        @Override
        public Response respond(Request request) {
            return this;
        }

        private void send(OutputStream output) throws IOException {
            StringBuilder header = new StringBuilder("HTTP/1.1 ").append(status).append(" Fixture\r\n")
                    .append(framing).append("Connection: close\r\n");
            boolean hasContentType = false;
            for (String extra : extraHeaders) {
                if (extra.regionMatches(true, 0, "Content-Type:", 0, 13)) hasContentType = true;
                header.append(extra).append("\r\n");
            }
            if (!hasContentType) header.append("Content-Type: application/octet-stream\r\n");
            output.write(header.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            body.writeTo(output);
            output.flush();
        }
    }

    static Response fixed(int status, byte[] body, String... headers) {
        return fixed(status, body, body.length, headers);
    }

    static Response fixed(int status, byte[] body, long declaredLength, String... headers) {
        return streamed(status, declaredLength, out -> out.write(body), headers);
    }

    static Response streamed(int status, long declaredLength, Body body, String... headers) {
        return new Response(status, "Content-Length: " + declaredLength + "\r\n", body, headers);
    }

    static Response chunked(int status, byte[] body, String... headers) throws IOException {
        return chunked(status, body, true, headers);
    }

    static Response chunked(int status, byte[] body, boolean complete, String... headers) throws IOException {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        if (body.length > 0) {
            chunks.write((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            chunks.write(body);
            chunks.write('\r');
            chunks.write('\n');
        }
        if (complete) chunks.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return new Response(status, "Transfer-Encoding: chunked\r\n", chunks::writeTo, headers);
    }

    private final ServerSocket listener = new ServerSocket();
    private final FutureTask<List<Request>> served;
    private volatile Socket activeSocket;

    LoopbackHttpFixture(Reply... replies) throws IOException {
        listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
        listener.setSoTimeout(5000);
        served = new FutureTask<>(() -> {
            List<Request> seen = new ArrayList<>();
            for (Reply reply : replies) {
                try (Socket socket = listener.accept()) {
                    activeSocket = socket;
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line = input.readLine();
                    String[] requestLine = line == null ? new String[0] : line.split(" ", 3);
                    if (requestLine.length != 3 || !("GET".equals(requestLine[0]) || "HEAD".equals(requestLine[0])) || !requestLine[1].startsWith("/")) {
                        throw new IOException("Unexpected fixture request: " + line);
                    }
                    Request request = new Request(requestLine[0], requestLine[1]);
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        int colon = line.indexOf(':');
                        if (colon != -1) request.headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
                    }
                    seen.add(request);
                    reply.respond(request).send(socket.getOutputStream());
                } finally {
                    activeSocket = null;
                }
            }
            return seen;
        });
        Thread thread = new Thread(served, "loopback-http-fixture");
        thread.setDaemon(true);
        thread.start();
    }

    String url() {
        return url("/fixture");
    }

    String url(String path) {
        return "http://127.0.0.1:" + listener.getLocalPort() + path;
    }

    List<Request> requests() throws Exception {
        return served.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        try {
            listener.close();
            Socket socket = activeSocket;
            if (socket != null) socket.close();
        } finally {
            served.cancel(true);
        }
    }
}
