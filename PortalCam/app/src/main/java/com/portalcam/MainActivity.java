package com.portalcam;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;

/**
 * Launcher activity. Starts the headless CameraService and, while it is on screen,
 * shows a live self-view by polling the service's own local endpoint
 * (127.0.0.1:8080/shot.jpg). The preview is just a plain HTTP client to localhost,
 * fully independent of the camera pipeline -- if it ever fails, streaming/webcam is
 * unaffected. Polling runs ONLY while the activity is resumed, so it costs nothing in
 * the common case where the Portal has paused this activity and only the service runs.
 */
public class MainActivity extends Activity {

    private static final String SHOT_URL = "http://127.0.0.1:8080/shot.jpg";
    private static final long FRAME_INTERVAL_MS = 33;   // ~30 fps preview
    private static final long RETRY_INTERVAL_MS = 500;  // server/camera not ready yet

    private ImageView preview;
    private TextView label;
    private Handler ui;
    private Thread pollThread;
    private volatile boolean polling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui = new Handler(Looper.getMainLooper());

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
            }, 1);
        } else {
            startCameraService();
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        label = new TextView(this);
        label.setText("PortalCam  •  http://" + getIpAddress() + ":8080/video");
        label.setTextSize(18);
        label.setTextColor(Color.WHITE);
        label.setShadowLayer(8, 0, 0, Color.BLACK);
        label.setGravity(Gravity.CENTER);
        label.setPadding(40, 24, 40, 24);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        root.addView(label, lp);

        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startCameraService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        pollThread = new Thread(() -> {
            while (polling) {
                Bitmap bmp = fetchFrame();
                if (bmp != null) {
                    ui.post(() -> { if (preview != null) preview.setImageBitmap(bmp); });
                    sleep(FRAME_INTERVAL_MS);
                } else {
                    sleep(RETRY_INTERVAL_MS);  // back off until the stream is live
                }
            }
        }, "preview-poll");
        pollThread.start();
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) { pollThread.interrupt(); pollThread = null; }
    }

    private static Bitmap fetchFrame() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(SHOT_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(2000);
            conn.setUseCaches(false);
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void startCameraService() {
        Intent i = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private static String getIpAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
