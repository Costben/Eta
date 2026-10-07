package io.github.costben.etavdprobe;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone app_process experiment. No Eta APK or LSPosed changes required. */
public final class ProbeMain {
    private static final String PACKAGE = "io.github.costben.etavdprobe";
    private static final String SOCKET = "eta_vd_probe";
    private static final boolean INDEPENDENT_DISPLAY = true;
    private final AtomicLong frames = new AtomicLong();
    private volatile long frameTimestamp;
    private volatile long frameChecksum;
    private UiAutomation automation;
    private VirtualDisplay virtual;
    private ImageReader reader;
    private PowerManager.WakeLock wakeLock;
    private HandlerThread callbacks;
    private Context context;
    private int flags;

    public static void main(String[] args) {
        ProbeMain probe = new ProbeMain();
        try { probe.run(); }
        catch (Throwable failure) {
            failure.printStackTrace();
            probe.close();
            System.exit(1);
        }
    }

    private void run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Object thread = threadType.getMethod("systemMain").invoke(null);
        Context system = (Context) threadType.getMethod("getSystemContext").invoke(thread);
        context = new ContextWrapper(system) {
            public String getPackageName() { return "com.android.shell"; }
            public String getOpPackageName() { return "com.android.shell"; }
            public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.SHELL_UID).setPackageName("com.android.shell").build();
            }
        };
        callbacks = new HandlerThread("eta-vd-probe-callbacks");
        callbacks.start();
        Handler handler = new Handler(callbacks.getLooper());
        reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image == null) return;
                java.nio.ByteBuffer bytes = image.getPlanes()[0].getBuffer();
                long hash = 1;
                for (int pos = 0; pos < bytes.limit(); pos += 4096) hash = hash * 31 + (bytes.get(pos) & 255);
                frameChecksum = hash;
                frameTimestamp = SystemClock.elapsedRealtime();
                frames.incrementAndGet();
            }
        }, handler);
        flags = 1 | 2 | 8 | 64 | 128 | 256 | 1024 | 8192 | 32768;
        if (INDEPENDENT_DISPLAY) flags |= 2048 | 4096 | 16384 | 65536;
        Constructor<DisplayManager> dmConstructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        dmConstructor.setAccessible(true);
        DisplayManager manager = dmConstructor.newInstance(context);
        virtual = manager.createVirtualDisplay("EtaVDProbe", 720, 1280, 320, reader.getSurface(), flags);
        if (virtual == null) throw new IllegalStateException("createVirtualDisplay returned null");
        PowerManager power = context.getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EtaVDProbe:cpu");
        wakeLock.acquire(30 * 60 * 1000L);
        connectAutomation();
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            System.out.println("ETA_VD_READY " + status().toString());
            System.out.flush();
            boolean running = true;
            while (running) {
                try (LocalSocket socket = server.accept()) {
                    int caller = socket.getPeerCredentials().getUid();
                    if (caller != 0 && caller != Process.SHELL_UID) continue;
                    socket.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    String line = in.readLine();
                    if (line == null || line.length() > 8192) continue;
                    JSONObject request = new JSONObject(line);
                    String action = request.optString("action", "status");
                    JSONObject result;
                    try {
                        if ("status".equals(action)) result = status();
                        else if ("nodes".equals(action)) result = nodes();
                        else if ("tap".equals(action)) {
                            boolean ok = tap(request.getInt("x"), request.getInt("y"));
                            result = new JSONObject().put("ok", ok).put("displayId", displayId());
                        } else if ("text".equals(action)) {
                            AccessibilityNodeInfo node = findInput();
                            android.os.Bundle args = new android.os.Bundle();
                            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.getString("text"));
                            boolean ok = node != null && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                            result = new JSONObject().put("ok", ok).put("displayId", displayId());
                        } else if ("stop".equals(action)) {
                            running = false;
                            result = new JSONObject().put("ok", true).put("action", "stop");
                        } else result = new JSONObject().put("ok", false).put("error", "UNKNOWN_ACTION");
                    } catch (Throwable error) {
                        result = new JSONObject().put("ok", false).put("error", error.toString());
                    }
                    out.println(result.toString());
                }
            }
        } finally { close(); }
        System.out.println("ETA_VD_RELEASED");
        System.out.flush();
        System.exit(0);
    }

    private void connectAutomation() throws Exception {
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        Constructor<UiAutomation> constructor = UiAutomation.class.getConstructor(Looper.class, connectionType);
        automation = constructor.newInstance(callbacks.getLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
    }

    private int displayId() { return virtual.getDisplay().getDisplayId(); }
    private JSONObject status() throws Exception {
        Display display = virtual.getDisplay();
        android.os.IBinder binder = (android.os.IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "window");
        Object window = Class.forName("android.view.IWindowManager$Stub")
            .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Class<?> windowType = Class.forName("android.view.IWindowManager");
        boolean locked = (Boolean) windowType.getMethod("isKeyguardLocked").invoke(window);
        boolean secure = (Boolean) windowType.getMethod("isKeyguardSecure", int.class).invoke(window, 0);
        return new JSONObject().put("ok", true).put("uid", Process.myUid()).put("pid", Process.myPid())
            .put("independent", INDEPENDENT_DISPLAY).put("flags", flags).put("displayId", displayId())
            .put("displayState", display.getState()).put("frames", frames.get())
            .put("frameAgeMs", frameTimestamp == 0 ? -1 : SystemClock.elapsedRealtime() - frameTimestamp)
            .put("frameChecksum", frameChecksum).put("deviceSecure", secure).put("keyguardLocked", locked)
            .put("cpuWakeLockHeld", wakeLock != null && wakeLock.isHeld());
    }

    private JSONObject nodes() throws Exception {
        JSONArray windows = new JSONArray();
        List<AccessibilityWindowInfo> list = automation.getWindowsOnAllDisplays().get(displayId());
        if (list != null) for (AccessibilityWindowInfo window : list) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !PACKAGE.contentEquals(root.getPackageName())) continue;
            JSONArray items = new JSONArray();
            collect(root, items, 0);
            windows.put(new JSONObject().put("windowId", window.getId()).put("displayId", window.getDisplayId())
                .put("active", window.isActive()).put("focused", window.isFocused()).put("nodes", items));
        }
        return new JSONObject().put("ok", true).put("displayId", displayId()).put("windows", windows);
    }

    private void collect(AccessibilityNodeInfo node, JSONArray out, int depth) throws Exception {
        if (depth > 12 || out.length() >= 100) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        out.put(new JSONObject().put("text", String.valueOf(node.getText()))
            .put("description", String.valueOf(node.getContentDescription()))
            .put("visible", node.isVisibleToUser()).put("focused", node.isFocused())
            .put("editable", node.isEditable()).put("bounds", new JSONArray(new int[] {bounds.left, bounds.top, bounds.right, bounds.bottom})));
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) collect(child, out, depth + 1);
        }
    }

    private AccessibilityNodeInfo findInput() {
        List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(displayId());
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && PACKAGE.contentEquals(root.getPackageName())) {
                AccessibilityNodeInfo found = findInput(root);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findInput(AccessibilityNodeInfo root) {
        if (root.isEditable() && root.isVisibleToUser()) return root;
        for (int index = 0; index < root.getChildCount(); index++) {
            AccessibilityNodeInfo child = root.getChild(index);
            if (child != null) { AccessibilityNodeInfo found = findInput(child); if (found != null) return found; }
        }
        return null;
    }

    private boolean tap(int x, int y) throws Exception {
        long now = SystemClock.uptimeMillis();
        Method setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        setDisplayId.invoke(down, displayId()); setDisplayId.invoke(up, displayId());
        try { return automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true); }
        finally { down.recycle(); up.recycle(); }
    }

    private void close() {
        if (automation != null) try { UiAutomation.class.getMethod("disconnect").invoke(automation); } catch (Exception ignored) {}
        if (virtual != null) virtual.release();
        if (reader != null) reader.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (callbacks != null) callbacks.quitSafely();
    }
}
