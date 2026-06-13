package com.portalcam;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Minimal HTTP server that streams the latest JPEG frame as MJPEG
 * (multipart/x-mixed-replace) at /video, and a single JPEG at /shot.jpg.
 * No external dependencies -- raw ServerSocket. Mirrors IP Webcam's endpoints
 * so it is a drop-in replacement in the existing OBS pipeline.
 */
public class MjpegServer {
    private static final String TAG = "PortalCam";
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final String BOUNDARY = "portalframe";

    private final int port;
    private final FrameHolder frames;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public MjpegServer(int port, FrameHolder frames) {
        this.port = port;
        this.frames = frames;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mjpeg-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "MJPEG server listening on :" + port);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handle(s), "mjpeg-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept error: " + e);
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), ASCII));
            String requestLine = in.readLine();
            // drain the rest of the request headers
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { /* ignore */ }

            String path = "/";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) path = parts[1];
            }

            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            if (path.startsWith("/video") || path.startsWith("/videofeed")) {
                serveMjpeg(out);
            } else if (path.startsWith("/shot") || path.startsWith("/photo")) {
                serveShot(out);
            } else {
                serveIndex(out);
            }
        } catch (IOException e) {
            // client disconnected -- normal
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void serveMjpeg(OutputStream out) throws IOException {
        String header =
                "HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n\r\n";
        out.write(header.getBytes(ASCII));
        out.flush();

        long lastSeq = -1;
        while (running) {
            FrameHolder.Frame f = frames.awaitNewer(lastSeq, 5000);
            if (f == null) continue;      // timeout, loop and check running
            lastSeq = f.seq;
            String part =
                    "--" + BOUNDARY + "\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: " + f.data.length + "\r\n\r\n";
            out.write(part.getBytes(ASCII));
            out.write(f.data);
            out.write("\r\n".getBytes(ASCII));
            out.flush();
        }
    }

    private void serveShot(OutputStream out) throws IOException {
        FrameHolder.Frame f = frames.latest();
        if (f == null) {
            out.write("HTTP/1.0 503 Service Unavailable\r\nConnection: close\r\n\r\n".getBytes(ASCII));
            out.flush();
            return;
        }
        String header =
                "HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, private\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + f.data.length + "\r\n\r\n";
        out.write(header.getBytes(ASCII));
        out.write(f.data);
        out.flush();
    }

    private void serveIndex(OutputStream out) throws IOException {
        String body = "<html><body style='background:#000;color:#ccc;font-family:sans-serif'>"
                + "<h2>PortalCam</h2>"
                + "<p><a style='color:#6cf' href='/video'>/video</a> (MJPEG stream)<br>"
                + "<a style='color:#6cf' href='/shot.jpg'>/shot.jpg</a> (single frame)</p>"
                + "<img src='/video' style='max-width:100%'></body></html>";
        byte[] b = body.getBytes(ASCII);
        String header =
                "HTTP/1.0 200 OK\r\n" +
                "Connection: close\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + b.length + "\r\n\r\n";
        out.write(header.getBytes(ASCII));
        out.write(b);
        out.flush();
    }

    /** Thread-safe holder for the latest encoded JPEG frame. */
    public static class FrameHolder {
        public static class Frame {
            public final byte[] data;
            public final long seq;
            Frame(byte[] data, long seq) { this.data = data; this.seq = seq; }
        }

        private byte[] latest;
        private long seq = 0;

        public synchronized void put(byte[] jpeg) {
            latest = jpeg;
            seq++;
            notifyAll();
        }

        public synchronized Frame latest() {
            return latest == null ? null : new Frame(latest, seq);
        }

        /** Block until a frame newer than afterSeq is available, or timeout. */
        public synchronized Frame awaitNewer(long afterSeq, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (seq <= afterSeq || latest == null) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) return null;
                try { wait(wait); } catch (InterruptedException e) { return null; }
            }
            return new Frame(latest, seq);
        }
    }
}
