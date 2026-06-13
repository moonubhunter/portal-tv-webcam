package com.portalcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Foreground service: opens the front camera via Camera2, JPEG-encodes each frame,
 * and hands them to the MJPEG server. Runs headless (no UI needed). Auto-retries the
 * camera open so a cold boot (where the Portal's Smart Camera service isn't ready yet
 * and throws ERROR_CAMERA_DISABLED) self-heals without a manual relaunch.
 */
public class CameraService extends Service {
    private static final String TAG = "PortalCam";
    private static final String CHANNEL_ID = "portalcam";
    private static final int NOTIF_ID = 1;

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int JPEG_QUALITY = 80;
    private static final int PORT = 8080;
    private static final int MAX_OPEN_ATTEMPTS = 20;
    private static final long RETRY_DELAY_MS = 1500;

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MjpegServer server;
    private MjpegServer.FrameHolder frames;
    private PowerManager.WakeLock wakeLock;
    private int openAttempts = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PortalCam::stream");
        wakeLock.acquire();

        frames = new MjpegServer.FrameHolder();
        server = new MjpegServer(PORT, frames);
        try {
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "server start failed", e);
        }

        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());

        openCamera();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void openCamera() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CAMERA permission not granted");
            return;
        }
        openAttempts++;
        // clean up any partial state from a previous attempt
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignored) {}

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String chosen = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { chosen = id; break; }
                if (chosen == null) chosen = id;
            }
            if (chosen == null) { Log.e(TAG, "no camera found"); scheduleRetry(); return; }
            Log.i(TAG, "opening camera " + chosen + " at " + WIDTH + "x" + HEIGHT + " (attempt " + openAttempts + ")");

            imageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(this::onImage, camHandler);

            cameraManager.openCamera(chosen, stateCallback, camHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (openAttempts >= MAX_OPEN_ATTEMPTS) {
            Log.e(TAG, "camera open: giving up after " + openAttempts + " attempts");
            return;
        }
        Log.i(TAG, "camera open: retrying in " + RETRY_DELAY_MS + "ms (after attempt " + openAttempts + ")");
        camHandler.postDelayed(this::openCamera, RETRY_DELAY_MS);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) {
            cameraDevice = device;
            try {
                device.createCaptureSession(
                        java.util.Collections.singletonList(imageReader.getSurface()),
                        sessionCallback, camHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "createCaptureSession failed", e);
                scheduleRetry();
            }
        }
        @Override public void onDisconnected(CameraDevice device) {
            Log.w(TAG, "camera disconnected");
            device.close(); cameraDevice = null;
            scheduleRetry();
        }
        @Override public void onError(CameraDevice device, int error) {
            Log.e(TAG, "camera error " + error + " (attempt " + openAttempts + ")");
            device.close(); cameraDevice = null;
            scheduleRetry();
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback() {
        @Override public void onConfigured(CameraCaptureSession session) {
            captureSession = session;
            try {
                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(imageReader.getSurface());
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
                session.setRepeatingRequest(b.build(), null, camHandler);
                openAttempts = 0;
                Log.i(TAG, "repeating request started");
            } catch (CameraAccessException e) {
                Log.e(TAG, "setRepeatingRequest failed", e);
                scheduleRetry();
            }
        }
        @Override public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "session configure failed");
            scheduleRetry();
        }
    };

    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            byte[] nv21 = yuv420ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(200 * 1024);
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), JPEG_QUALITY, baos);
            frames.put(baos.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "frame encode error: " + e);
        } finally {
            if (image != null) image.close();
        }
    }

    private static byte[] yuv420ToNv21(Image image) {
        int w = image.getWidth(), h = image.getHeight();
        Image.Plane[] p = image.getPlanes();
        ByteBuffer yBuf = p[0].getBuffer();
        ByteBuffer uBuf = p[1].getBuffer();
        ByteBuffer vBuf = p[2].getBuffer();
        int ySize = w * h;
        byte[] nv21 = new byte[ySize + ySize / 2];

        int yRowStride = p[0].getRowStride();
        if (yRowStride == w) {
            yBuf.get(nv21, 0, ySize);
        } else {
            for (int row = 0; row < h; row++) {
                yBuf.position(row * yRowStride);
                yBuf.get(nv21, row * w, w);
            }
        }

        int uvRowStride = p[1].getRowStride();
        int uvPixStride = p[1].getPixelStride();
        int chromaH = h / 2, chromaW = w / 2;
        int offset = ySize;
        for (int row = 0; row < chromaH; row++) {
            int rowStart = row * uvRowStride;
            for (int col = 0; col < chromaW; col++) {
                int uvPos = rowStart + col * uvPixStride;
                nv21[offset++] = vBuf.get(uvPos); // V
                nv21[offset++] = uBuf.get(uvPos); // U
            }
        }
        return nv21;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "PortalCam", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder nb = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("PortalCam")
                .setContentText("Streaming camera on :" + PORT)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, n);
    }

    @Override
    public void onDestroy() {
        try { if (captureSession != null) captureSession.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        if (server != null) server.stop();
        if (camThread != null) camThread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
