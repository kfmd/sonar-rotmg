package sonar.ui;

import com.sun.jna.Function;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * macOS window-system adapter. The rest of Sonar remains Java/Swing; this class is the
 * deliberately small boundary to CoreGraphics, CoreFoundation and AppKit.
 *
 * Screen capture is limited to the ROTMG Exalt window (not the desktop), and no game
 * input is changed. The AppKit call only changes hit-testing on Sonar's own overlay.
 */
final class MacOSNative {
    private MacOSNative() {}

    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final int KCF_UTF8 = 0x08000100;
    private static final int KCF_NUMBER_DOUBLE = 13;
    private static final int CG_WINDOW_LIST_ON_SCREEN_ONLY = 1;
    private static final int CG_WINDOW_LIST_INCLUDE_WINDOW = 8;
    private static final int CG_WINDOW_IMAGE_BOUNDS_IGNORE_FRAMING = 1;
    private static final int CG_BITMAP_BYTE_ORDER_32_LITTLE = 0x2000;
    private static final int CG_BITMAP_BYTE_ORDER_32_BIG = 0x4000;

    private interface CG extends Library {
        Pointer CGWindowListCopyWindowInfo(int option, int relativeToWindow);
        Pointer CGWindowListCreateImage(CGRect screenBounds, int option, int windowId, int imageOptions);
        int CGImageGetWidth(Pointer image);
        int CGImageGetHeight(Pointer image);
        long CGImageGetBytesPerRow(Pointer image);
        int CGImageGetBitsPerPixel(Pointer image);
        int CGImageGetBitmapInfo(Pointer image);
        Pointer CGImageGetDataProvider(Pointer image);
        Pointer CGDataProviderCopyData(Pointer provider);
        void CGImageRelease(Pointer image);
        int CGWindowLevelForKey(int key);
    }

    private interface CF extends Library {
        long CFArrayGetCount(Pointer array);
        Pointer CFArrayGetValueAtIndex(Pointer array, long index);
        Pointer CFDictionaryGetValue(Pointer dictionary, Pointer key);
        Pointer CFStringCreateWithCString(Pointer allocator, String value, int encoding);
        byte CFStringGetCString(Pointer string, Pointer buffer, long size, int encoding);
        byte CFNumberGetValue(Pointer number, int type, Pointer value);
        Pointer CFDataGetBytePtr(Pointer data);
        long CFDataGetLength(Pointer data);
        void CFRelease(Pointer value);
    }

    @Structure.FieldOrder({"x", "y", "width", "height"})
    public static class CGRect extends Structure implements Structure.ByValue {
        // JNA reads Structure fields through reflection. The Structure class itself must
        // also be publicly accessible; a private nested class can fail at runtime with
        // "Exception reading field 'x'", even when the fields are public.
        public double x, y, width, height;

        public CGRect() {}

        public CGRect(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static CG cg;
    private static CF cf;
    private static Throwable nativeLoadFailure;
    private static volatile boolean captureReadyLogged;
    private static volatile boolean captureNullLogged;
    private static volatile String lastCaptureFailure;
    private static volatile int lastSelectedGameWindowId;
    private static volatile String lastPeerDiagnostic = "";


    /**
     * NSWindow is an AppKit main-actor object. AWT's EventQueue thread is not
     * necessarily the Cocoa main thread, so AppKit messages must be dispatched
     * to the main queue rather than sent directly from the AWT event thread.
     *
     * dispatch_sync_f makes the caller wait until the native operation finishes.
     * pthread_main_np prevents synchronously dispatching to the main queue from
     * that queue's own thread, which would deadlock.
     */
    private interface DispatchWork extends Callback {
        void invoke(Pointer context);
    }

    private interface MainDispatch extends Library {
        void dispatch_sync_f(Pointer queue, Pointer context, DispatchWork work);
        int pthread_main_np();
    }

    private interface MainThreadAction<T> {
        T run() throws Throwable;
    }

    private static final class MainThreadResult<T> {
        volatile T value;
        volatile Throwable failure;
    }

    private static volatile MainDispatch mainDispatch;
    private static volatile NativeLibrary dispatchLibrary;
    private static volatile Pointer mainQueueAddress;

    /**
     * dispatch_get_main_queue() is an inline function in Apple's headers,
     * not an exported function. Its implementation returns &_dispatch_main_q.
     * Resolve that exported global directly instead.
     */
    private static Pointer mainQueue() {
        Pointer current = mainQueueAddress;
        if (current != null) return current;

        synchronized (MacOSNative.class) {
            if (mainQueueAddress == null) {
                if (dispatchLibrary == null) {
                    dispatchLibrary = NativeLibrary.getInstance(
                            "/usr/lib/system/libdispatch.dylib");
                }
                mainQueueAddress =
                        dispatchLibrary.getGlobalVariableAddress("_dispatch_main_q");

                if (mainQueueAddress == null) {
                    throw new UnsatisfiedLinkError(
                            "Could not resolve libdispatch global _dispatch_main_q");
                }
            }
            return mainQueueAddress;
        }
    }

    private static MainDispatch mainDispatch() {
        MainDispatch current = mainDispatch;
        if (current != null) return current;
        synchronized (MacOSNative.class) {
            if (mainDispatch == null) {
                // libSystem re-exports libdispatch and pthread's public entry points.
                mainDispatch = Native.load("System", MainDispatch.class);
            }
            return mainDispatch;
        }
    }

    private static <T> T onAppKitMainThread(MainThreadAction<T> action) throws Throwable {
        MainDispatch dispatch = mainDispatch();
        if (dispatch.pthread_main_np() != 0) return action.run();

        Pointer queue = mainQueue();
        if (queue == null) throw new IllegalStateException("dispatch_get_main_queue returned null");

        MainThreadResult<T> result = new MainThreadResult<>();
        // dispatch_sync_f is synchronous; these locals remain strongly reachable until it returns.
        Memory context = new Memory(1);
        context.clear();
        DispatchWork work = ignored -> {
            try {
                result.value = action.run();
            } catch (Throwable failure) {
                result.failure = failure;
            }
        };
        dispatch.dispatch_sync_f(queue, context, work);
        if (result.failure != null) throw result.failure;
        return result.value;
    }

    private static Pointer appKitPointer(Function msg, Object[] args) throws Throwable {
        return onAppKitMainThread(() -> msg.invokePointer(args));
    }

    private static Object appKitValue(Function msg, Class<?> returnType, Object[] args) throws Throwable {
        return onAppKitMainThread(() -> msg.invoke(returnType, args));
    }

    private static void appKitVoid(Function msg, Object[] args) throws Throwable {
        onAppKitMainThread(() -> {
            msg.invokeVoid(args);
            return null;
        });
    }

    private static synchronized boolean load() {
        if (!MAC) return false;
        if (cg != null && cf != null) return true;
        if (nativeLoadFailure != null) return false;
        try {
            cg = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", CG.class);
            cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CF.class);
            return true;
        } catch (Throwable t) {
            nativeLoadFailure = t;
            System.err.println("[mac] CoreGraphics could not be loaded: " + t);
            return false;
        }
    }

    static final class GameWindow {
        final int id;
        final int pid;
        final String owner;
        final String title;
        final Rectangle bounds;
        GameWindow(int id, int pid, String owner, String title, Rectangle bounds) {
            this.id = id; this.pid = pid; this.owner = owner; this.title = title; this.bounds = bounds;
        }
    }

    private static Pointer key(String value) {
        return cf.CFStringCreateWithCString(null, value, KCF_UTF8);
    }

    private static Pointer value(Pointer dictionary, String name) {
        if (dictionary == null) return null;
        Pointer k = key(name);
        try { return cf.CFDictionaryGetValue(dictionary, k); }
        finally { if (k != null) cf.CFRelease(k); }
    }

    private static String string(Pointer p) {
        if (p == null) return "";
        Memory buffer = new Memory(2048);
        buffer.clear();
        if (cf.CFStringGetCString(p, buffer, buffer.size(), KCF_UTF8) == 0) return "";
        return buffer.getString(0, "UTF-8");
    }

    private static double number(Pointer p, double fallback) {
        if (p == null) return fallback;
        Memory out = new Memory(8);
        out.clear();
        if (cf.CFNumberGetValue(p, KCF_NUMBER_DOUBLE, out) == 0) return fallback;
        return out.getDouble(0);
    }

    private static boolean number(Pointer dict, String key, int fallback) {
        return number(value(dict, key), fallback) != 0;
    }

    /**
     * Match the Unity gameplay process only. The Steam launcher is a separate process
     * ("RotMG Exalt Launcher") and must never be treated as the game: doing so makes
     * the overlay appear over the launcher and routes clicks to the wrong application.
     * The current macOS Unity process is reported by WindowServer as "RotMGExalt".
     */
    private static boolean isGame(String owner, String title) {
        String rawOwner = owner == null ? "" : owner.trim().toLowerCase(java.util.Locale.ROOT);
        String rawTitle = title == null ? "" : title.trim().toLowerCase(java.util.Locale.ROOT);
        if (rawOwner.contains("launcher") || rawTitle.contains("launcher")) return false;
        String normalizedOwner = rawOwner.replaceAll("[^a-z0-9]", "");
        // Prefer the process owner over a window title. Titles can be empty or generic;
        // using a broad title substring reintroduced the launcher false-positive.
        return normalizedOwner.equals("rotmgexalt")
                || normalizedOwner.equals("realmofthemadgodexalt")
                || normalizedOwner.equals("realmofthemadgod");
    }

    private static long currentPid() {
        try {
            String runtime = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = runtime.indexOf('@');
            return Long.parseLong(at < 0 ? runtime : runtime.substring(0, at));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean isSonarProcess(String owner, int pid) {
        String lower = owner == null ? "" : owner.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("sonar") || lower.startsWith("sonar ") || lower.equals("java")
                || lower.contains("openjdk") || pid == currentPid();
    }

    /** A single WindowServer snapshot shared by all callers; never enumerate on the EDT. */
    private static final class WindowSnapshot {
        final GameWindow game;
        final boolean gameInFront;
        final long capturedAt;

        WindowSnapshot(GameWindow game, boolean gameInFront, long capturedAt) {
            this.game = game;
            this.gameInFront = gameInFront;
            this.capturedAt = capturedAt;
        }
    }

    private static volatile WindowSnapshot windowSnapshot;
    private static volatile String lastFrontDiagnostic = "";
    private static volatile String lastWindowScanFailure = "";
    private static final java.util.concurrent.atomic.AtomicBoolean windowMonitorStarted =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile java.util.concurrent.ScheduledExecutorService windowMonitorExecutor;

    private static void ensureWindowMonitor() {
        if (!MAC || !windowMonitorStarted.compareAndSet(false, true)) return;
        try {
            java.util.concurrent.ScheduledExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "sonar-macos-window-monitor");
                        t.setDaemon(true);
                        return t;
                    });
            windowMonitorExecutor = executor;
            executor.scheduleWithFixedDelay(MacOSNative::refreshWindowSnapshotSafely,
                    0L, 250L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            windowMonitorStarted.set(false);
            System.err.println("[mac] could not start background window monitor: " + failure);
        }
    }

    private static void refreshWindowSnapshotSafely() {
        if (!load()) return;
        Pointer array = null;
        try {
            array = cg.CGWindowListCopyWindowInfo(CG_WINDOW_LIST_ON_SCREEN_ONLY, 0);
            if (array == null) {
                windowSnapshot = new WindowSnapshot(null, false, System.currentTimeMillis());
                return;
            }

            long count = cf.CFArrayGetCount(array);
            GameWindow best = null;
            long bestArea = 0;
            int gameWindows = 0, rejectedGeometry = 0;
            boolean foregroundResolved = false;
            boolean foregroundIsGame = false;
            String frontDiagnostic = "<none>";

            for (long i = 0; i < count; i++) {
                Pointer item = cf.CFArrayGetValueAtIndex(array, i);
                if (item == null) continue;
                int layer = (int) number(value(item, "kCGWindowLayer"), -1);
                if (layer != 0) continue;

                String owner = string(value(item, "kCGWindowOwnerName"));
                String title = string(value(item, "kCGWindowName"));
                int pid = (int) number(value(item, "kCGWindowOwnerPID"), 0);

                // The first non-Sonar layer-0 window is the frontmost application window.
                if (!foregroundResolved && !isSonarProcess(owner, pid)) {
                    foregroundResolved = true;
                    foregroundIsGame = isGame(owner, title);
                    frontDiagnostic = owner + " (pid=" + pid + ") -> "
                            + (foregroundIsGame ? "game" : "not game");
                }

                if (!isGame(owner, title)) continue;
                int id = (int) number(value(item, "kCGWindowNumber"), 0);
                Pointer bounds = value(item, "kCGWindowBounds");
                double x = number(value(bounds, "X"), Double.NaN);
                double y = number(value(bounds, "Y"), Double.NaN);
                double wd = number(value(bounds, "Width"), 0);
                double ht = number(value(bounds, "Height"), 0);
                if (id <= 0 || Double.isNaN(x) || Double.isNaN(y) || wd <= 0 || ht <= 0) continue;
                gameWindows++;

                double aspect = wd / ht;
                // Reject auxiliary launcher strips and splash surfaces; select the largest
                // plausible Unity gameplay window belonging to RotMGExalt.
                if (wd < 320 || ht < 240 || aspect < 0.55 || aspect > 4.0
                        || wd > 12000 || ht > 12000) {
                    rejectedGeometry++;
                    continue;
                }
                long area = (long) Math.ceil(wd) * (long) Math.ceil(ht);
                if (area > bestArea) {
                    bestArea = area;
                    best = new GameWindow(id, pid, owner, title,
                            new Rectangle((int) Math.round(x), (int) Math.round(y),
                                    (int) Math.round(wd), (int) Math.round(ht)));
                }
            }

            if (best != null && best.id != lastSelectedGameWindowId) {
                System.err.println("[mac] selected Exalt game window=" + best.id + ", owner=" + best.owner
                        + ", bounds=" + best.bounds + ", candidates=" + gameWindows
                        + ", rejected auxiliary geometry=" + rejectedGeometry);
                lastSelectedGameWindowId = best.id;
            } else if (best == null && gameWindows > 0 && lastSelectedGameWindowId != 0) {
                System.err.println("[mac] no plausible Exalt client window among " + gameWindows
                        + " game-owned windows (rejected " + rejectedGeometry + ")");
                lastSelectedGameWindowId = 0;
            }

            if (!frontDiagnostic.equals(lastFrontDiagnostic)) {
                System.err.println("[mac] frontmost application: " + frontDiagnostic);
                lastFrontDiagnostic = frontDiagnostic;
            }
            windowSnapshot = new WindowSnapshot(best, foregroundResolved && foregroundIsGame,
                    System.currentTimeMillis());
            lastWindowScanFailure = "";
        } catch (Throwable t) {
            String failure = t.getClass().getName() + ": " + String.valueOf(t.getMessage());
            if (!failure.equals(lastWindowScanFailure)) {
                System.err.println("[mac] background window snapshot failed: " + failure);
                lastWindowScanFailure = failure;
            }
        } finally {
            if (array != null) {
                try { cf.CFRelease(array); } catch (Throwable ignored) {}
            }
        }
    }

    /** Returns the cached visible Exalt game window; the WindowServer query runs off-thread. */
    static GameWindow gameWindow() {
        if (!MAC) return null;
        ensureWindowMonitor();
        WindowSnapshot snapshot = windowSnapshot;
        if (snapshot == null || System.currentTimeMillis() - snapshot.capturedAt > 2000L
                || snapshot.game == null) return null;
        GameWindow game = snapshot.game;
        return new GameWindow(game.id, game.pid, game.owner, game.title, new Rectangle(game.bounds));
    }

    /** True when the last background snapshot says the game is the frontmost non-Sonar app. */
    static boolean gameInFront() {
        if (!MAC) return false;
        ensureWindowMonitor();
        WindowSnapshot snapshot = windowSnapshot;
        return snapshot != null && System.currentTimeMillis() - snapshot.capturedAt <= 2000L
                && snapshot.gameInFront;
    }

    /**
     * Captures only the game window, then crops the requested logical desktop rectangle.
     * This avoids photographing Sonar's own overlay while reading the minimap underneath it.
     * macOS may ask the user to grant Screen & System Audio Recording access to Sonar.
     */
    static BufferedImage captureGameRect(Rectangle requested) {
        if (!load() || requested == null) return null;
        GameWindow game = gameWindow();
        if (game == null) return null;
        Pointer image = null;
        try {
            CGRect nullRect = new CGRect(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0);
            image = cg.CGWindowListCreateImage(nullRect, CG_WINDOW_LIST_INCLUDE_WINDOW,
                    game.id, CG_WINDOW_IMAGE_BOUNDS_IGNORE_FRAMING);
            if (image == null) {
                if (!captureNullLogged) {
                    System.err.println("[mac] CoreGraphics returned no image for Exalt window "
                            + game.id + " (" + game.owner + "). Check Screen & System Audio Recording permission.");
                    captureNullLogged = true;
                }
                return null;
            }
            int iw = cg.CGImageGetWidth(image), ih = cg.CGImageGetHeight(image);
            long rowBytes = cg.CGImageGetBytesPerRow(image);
            if (iw <= 0 || ih <= 0 || rowBytes < (long) iw * 4) {
                System.err.println("[mac] invalid Exalt capture image: " + iw + "x" + ih
                        + ", bytesPerRow=" + rowBytes);
                return null;
            }
            if (!captureReadyLogged) {
                System.err.println("[mac] CoreGraphics capture active: window=" + game.id
                        + ", owner=" + game.owner + ", bounds=" + game.bounds
                        + ", image=" + iw + "x" + ih + ", scale="
                        + String.format(java.util.Locale.ROOT, "%.3f", iw / (double) game.bounds.width) + "x"
                        + String.format(java.util.Locale.ROOT, "%.3f", ih / (double) game.bounds.height));
                captureReadyLogged = true;
                captureNullLogged = false;
                lastCaptureFailure = null;
            }
            Pointer provider = cg.CGImageGetDataProvider(image);
            if (provider == null) return null;
            Pointer copied = cg.CGDataProviderCopyData(provider);
            if (copied == null) return null;
            try {
                long length = cf.CFDataGetLength(copied);
                Pointer bytes = cf.CFDataGetBytePtr(copied);
                if (bytes == null || length < rowBytes * ih) return null;
                double sx = iw / (double) game.bounds.width;
                double sy = ih / (double) game.bounds.height;
                int left = (int) Math.floor((requested.x - game.bounds.x) * sx);
                int top = (int) Math.floor((requested.y - game.bounds.y) * sy);
                int right = (int) Math.ceil((requested.x + requested.width - game.bounds.x) * sx);
                int bottom = (int) Math.ceil((requested.y + requested.height - game.bounds.y) * sy);
                left = Math.max(0, Math.min(iw, left));
                top = Math.max(0, Math.min(ih, top));
                right = Math.max(left, Math.min(iw, right));
                bottom = Math.max(top, Math.min(ih, bottom));
                if (right <= left || bottom <= top) return null;
                int outW = right - left, outH = bottom - top;
                BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
                int bitmap = cg.CGImageGetBitmapInfo(image);
                int alpha = bitmap & 0x1f;
                int order = bitmap & 0x7000;
                boolean little = order == CG_BITMAP_BYTE_ORDER_32_LITTLE;
                boolean alphaFirst = alpha == 2 || alpha == 4;
                boolean alphaLast = alpha == 1 || alpha == 3;
                boolean bgra = little && alphaFirst;
                boolean abgr = little && alphaLast;
                boolean argb = !little && alphaFirst;
                boolean rgba = !little && alphaLast;
                // Copy only the requested crop, one contiguous row at a time. Calling
                // JNA getByte() four times per pixel and BufferedImage.setRGB() once per
                // pixel made the capture path needlessly expensive on Retina-sized windows.
                if (outW > Integer.MAX_VALUE / 4) return null;
                byte[] row = new byte[outW * 4];
                int[] pixels = ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
                for (int y = 0; y < outH; y++) {
                    long sourceRow = (long) (top + y) * rowBytes + (long) left * 4;
                    bytes.read(sourceRow, row, 0, row.length);
                    int dest = y * outW;
                    for (int x = 0, p = 0; x < outW; x++, p += 4) {
                        int c0 = row[p] & 255;
                        int c1 = row[p + 1] & 255;
                        int c2 = row[p + 2] & 255;
                        int c3 = row[p + 3] & 255;
                        int r, g, b;
                        if (bgra) { b = c0; g = c1; r = c2; }
                        else if (abgr) { r = c3; g = c2; b = c1; }
                        else if (argb) { r = c1; g = c2; b = c3; }
                        else if (rgba) { r = c0; g = c1; b = c2; }
                        else { b = c0; g = c1; r = c2; }
                        pixels[dest + x] = (r << 16) | (g << 8) | b;
                    }
                }
                return out;
            } finally {
                cf.CFRelease(copied);
            }
        } catch (Throwable t) {
            String failure = t.getClass().getName() + ": " + String.valueOf(t.getMessage());
            if (!failure.equals(lastCaptureFailure)) {
                System.err.println("[mac] game-window capture failed: " + failure);
                lastCaptureFailure = failure;
            }
            return null;
        } finally {
            if (image != null) {
                try { cg.CGImageRelease(image); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Configures the minimap overlay's native NSWindow. Call only after the AWT peer exists
     * and the window has been shown. This is intentionally separate from click-through so
     * the 300 ms foreground poll never changes the window level/Space membership repeatedly.
     */
    static boolean configureOverlayWindow(Window window) {
        if (!MAC || window == null) return false;
        try {
            load();
            long handle = nativeWindowPointer(window);
            if (handle == 0) return false;
            NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
            Function msg = objc.getFunction("objc_msgSend");
            Function selector = objc.getFunction("sel_registerName");
            Function getClass = objc.getFunction("objc_getClass");
            Pointer nativeWindow = new Pointer(handle);

            Pointer nsColor = getClass.invokePointer(new Object[]{"NSColor"});
            Pointer clearColorSel = selector.invokePointer(new Object[]{"clearColor"});
            Pointer clearColor = appKitPointer(msg, new Object[]{nsColor, clearColorSel});
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setOpaque:"}), (byte) 0});
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setBackgroundColor:"}), clearColor});
            // Set the cosmetic shadow property on AppKit's main queue as well.
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setHasShadow:"}), (byte) 0});
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setHidesOnDeactivate:"}), (byte) 0});
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setAcceptsMouseMovedEvents:"}), (byte) 0});

            // Use both the legacy native-fullscreen auxiliary role and the newer
            // cross-application collection behavior. FullScreenAuxiliary (256) is the
            // AppKit behavior that places a floating companion window in another app's
            // native-fullscreen Space. CanJoinAllApplications (262144) covers newer
            // Stage Manager / cross-application Space management. They serve different
            // collection mechanisms; clear conflicting primary/none/auxiliary roles first.
            Pointer behaviorGetter = selector.invokePointer(new Object[]{"collectionBehavior"});
            Object oldValue = appKitValue(msg, Long.class, new Object[]{nativeWindow, behaviorGetter});
            long oldBehavior = oldValue instanceof Number ? ((Number) oldValue).longValue() : 0L;
            final long canJoinAllSpaces = 1L;
            final long fullScreenAuxiliary = 256L;
            final long fullScreenNone = 512L;
            final long canJoinAllApplications = 262144L;
            final long roleMask = 128L | fullScreenAuxiliary | fullScreenNone
                    | 65536L | 131072L;
            long wantedBehavior = (oldBehavior & ~roleMask)
                    | canJoinAllSpaces | fullScreenAuxiliary | canJoinAllApplications;
            Pointer behaviorSetter = selector.invokePointer(new Object[]{"setCollectionBehavior:"});
            appKitVoid(msg, new Object[]{nativeWindow, behaviorSetter, Long.valueOf(wantedBehavior)});
            Object actualValue = appKitValue(msg, Long.class, new Object[]{nativeWindow, behaviorGetter});
            long actualBehavior = actualValue instanceof Number ? ((Number) actualValue).longValue() : -1L;
            boolean joinsAllSpaces = (actualBehavior & canJoinAllSpaces) != 0;
            boolean joinsAllApplications = (actualBehavior & canJoinAllApplications) != 0;
            boolean fullScreenAux = (actualBehavior & fullScreenAuxiliary) != 0;

            // Use the system status-window level rather than a hard-coded floating level.
            // The status level is above ordinary application windows, including many Unity
            // borderless/full-screen windows, but below menus and system-critical surfaces.
            int statusLevel = -1;
            try { statusLevel = cg.CGWindowLevelForKey(9); } catch (Throwable ignored) {}
            if (statusLevel <= 0) statusLevel = 3; // NSFloatingWindowLevel fallback
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setLevel:"}), Long.valueOf(statusLevel)});

            Object opaqueValue = appKitValue(msg, Byte.class,
                    new Object[]{nativeWindow, selector.invokePointer(new Object[]{"isOpaque"})});
            boolean opaque = opaqueValue instanceof Number && ((Number) opaqueValue).byteValue() != 0;
            Pointer backgroundColor = appKitPointer(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"backgroundColor"})});
            Object alphaValue = backgroundColor == null ? null : appKitValue(msg, Double.class,
                    new Object[]{backgroundColor, selector.invokePointer(new Object[]{"alphaComponent"})});
            double backgroundAlpha = alphaValue instanceof Number ? ((Number) alphaValue).doubleValue() : -1;
            Object levelValue = appKitValue(msg, Long.class,
                    new Object[]{nativeWindow, selector.invokePointer(new Object[]{"level"})});
            long actualLevel = levelValue instanceof Number ? ((Number) levelValue).longValue() : Long.MIN_VALUE;
            Object numberValue = appKitValue(msg, Long.class,
                    new Object[]{nativeWindow, selector.invokePointer(new Object[]{"windowNumber"})});
            long windowNumber = numberValue instanceof Number ? ((Number) numberValue).longValue() : -1L;

            System.err.println("[mac] overlay NSWindow configured: number=" + windowNumber
                    + ", opaque=" + opaque
                    + ", backgroundAlpha=" + backgroundAlpha
                    + ", canJoinAllSpaces=" + joinsAllSpaces
                    + ", fullScreenAuxiliary=" + fullScreenAux
                    + ", canJoinAllApplications=" + joinsAllApplications
                    + ", collectionBehavior=" + actualBehavior
                    + ", level=" + actualLevel + " (status-level=" + statusLevel + ")");
            if (opaque || !joinsAllSpaces || !fullScreenAux || !joinsAllApplications) {
                System.err.println("[mac] WARNING: overlay native style verification failed; "
                        + "fullscreen membership or per-pixel transparency may not work");
            }
            return true;
        } catch (Throwable t) {
            System.err.println("[mac] could not configure overlay NSWindow: " + t);
            return false;
        }
    }

    /**
     * Returns the native hit-test state, or null while AWT has no usable NSWindow peer.
     * Null is deliberately different from false: callers must not interpret "not yet
     * available" as a native click-through flag that macOS reset.
     */
    static Boolean clickThroughState(Window window) {
        if (!MAC || window == null) return null;
        try {
            long handle = nativeWindowPointer(window);
            if (handle == 0) return null;
            NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
            Function msg = objc.getFunction("objc_msgSend");
            Function selector = objc.getFunction("sel_registerName");
            Object result = appKitValue(msg, Byte.class, new Object[]{new Pointer(handle),
                    selector.invokePointer(new Object[]{"ignoresMouseEvents"})});
            if (!(result instanceof Number)) return null;
            return ((Number) result).byteValue() != 0;
        } catch (Throwable t) {
            System.err.println("[mac] could not read overlay click-through state: " + t);
            return null;
        }
    }

    /** Compatibility helper; use clickThroughState when "not yet available" matters. */
    static boolean isClickThrough(Window window) {
        return Boolean.TRUE.equals(clickThroughState(window));
    }

    /** Returns zero until this AWT window has a native NSWindow peer. */
    static long nativeWindowHandle(Window window) {
        if (!MAC || window == null) return 0L;
        if (!window.isDisplayable()) {
            reportPeerDiagnostic(window, "AWT window is not displayable");
            return 0L;
        }
        try {
            return nativeWindowPointer(window);
        } catch (Throwable t) {
            reportPeerDiagnostic(window, "native handle lookup threw " + t);
            return 0L;
        }
    }

    private static void reportPeerDiagnostic(Window window, String detail) {
        String line = "[mac] overlay native peer lookup: " + detail
                + " [displayable=" + window.isDisplayable()
                + ", visible=" + window.isVisible()
                + ", showing=" + window.isShowing()
                + ", type=" + window.getClass().getName() + "]";
        if (!line.equals(lastPeerDiagnostic)) {
            System.err.println(line);
            lastPeerDiagnostic = line;
        }
    }

    static boolean setClickThrough(Window window, boolean through) {
        if (!MAC || window == null) return false;
        try {
            long handle = nativeWindowPointer(window);
            if (handle == 0) return false;
            NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
            Function msg = objc.getFunction("objc_msgSend");
            Function selector = objc.getFunction("sel_registerName");
            Pointer nativeWindow = new Pointer(handle);
            Pointer setter = selector.invokePointer(new Object[]{"setIgnoresMouseEvents:"});
            appKitVoid(msg, new Object[]{nativeWindow, setter, (byte) (through ? 1 : 0)});
            appKitVoid(msg, new Object[]{nativeWindow,
                    selector.invokePointer(new Object[]{"setAcceptsMouseMovedEvents:"}), (byte) 0});
            Pointer getter = selector.invokePointer(new Object[]{"ignoresMouseEvents"});
            Object result = appKitValue(msg, Byte.class, new Object[]{nativeWindow, getter});
            byte actual = result instanceof Number ? ((Number) result).byteValue() : 0;
            boolean confirmed = (actual != 0) == through;
            Object numberValue = appKitValue(msg, Long.class,
                    new Object[]{nativeWindow, selector.invokePointer(new Object[]{"windowNumber"})});
            long windowNumber = numberValue instanceof Number ? ((Number) numberValue).longValue() : -1L;
            System.err.println("[mac] overlay input: NSWindow=" + windowNumber
                    + ", requested=" + (through ? "click-through" : "interactive")
                    + ", ignoresMouseEvents=" + (actual != 0)
                    + (confirmed ? " (verified)" : " (NOT VERIFIED)"));
            return confirmed;
        } catch (Throwable t) {
            System.err.println("[mac] could not set overlay click-through: " + t);
            return false;
        }
    }

    /** Reasserts front ordering once when the overlay is shown, not on the polling timer. */
    static void orderOverlayFront(Window window) {
        if (!MAC || window == null) return;
        try {
            long handle = nativeWindowPointer(window);
            if (handle == 0) return;
            NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
            Function msg = objc.getFunction("objc_msgSend");
            Function selector = objc.getFunction("sel_registerName");
            appKitVoid(msg, new Object[]{new Pointer(handle),
                    selector.invokePointer(new Object[]{"orderFrontRegardless"})});
        } catch (Throwable t) {
            System.err.println("[mac] could not order overlay above game: " + t);
        }
    }

    private static long nativeWindowPointer(Window window) throws Exception {
        if (window == null) return 0L;
        if (!window.isDisplayable()) {
            reportPeerDiagnostic(window, "AWT window is not displayable yet");
            return 0L;
        }

        Class<?> accessorClass = Class.forName("sun.awt.AWTAccessor");
        Object componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null);
        Class<?> componentAccessorType = Class.forName("sun.awt.AWTAccessor$ComponentAccessor");
        Method getPeer = componentAccessorType.getMethod("getPeer", java.awt.Component.class);
        Object peer = getPeer.invoke(componentAccessor, window);
        if (peer == null) {
            reportPeerDiagnostic(window, "AWT ComponentAccessor returned peer=null");
            return 0L;
        }

        // Most OpenJDK 17/21 macOS peers are LWWindowPeer -> CPlatformWindow. Some
        // vendor runtimes expose the platform-window accessor directly on the peer,
        // while others wrap it in a peer subclass. Search the complete superclass chain.
        Object platform = peer;
        Method getPlatformWindow = findMethod(peer.getClass(), "getPlatformWindow");
        if (getPlatformWindow != null) {
            getPlatformWindow.setAccessible(true);
            Object candidate = getPlatformWindow.invoke(peer);
            if (candidate != null) platform = candidate;
            else {
                reportPeerDiagnostic(window, "peer=" + peer.getClass().getName()
                        + " returned null from getPlatformWindow()");
                return 0L;
            }
        } else {
            // Runtime-specific peer wrappers sometimes expose the platform peer as a field
            // rather than a method. Use it only as a fallback; the OpenJDK accessor remains
            // the preferred route.
            Object candidate = readField(peer, "platformWindow");
            if (candidate != null) platform = candidate;
        }

        Method getNSWindowPtr = findMethod(platform.getClass(), "getNSWindowPtr");
        if (getNSWindowPtr != null) getNSWindowPtr.setAccessible(true);
        Object value = getNSWindowPtr == null ? null : getNSWindowPtr.invoke(platform);
        long ptr = value instanceof Number ? ((Number) value).longValue() : 0L;
        if (ptr == 0L) {
            Object fieldValue = readField(platform, "ptr");
            if (fieldValue instanceof Number) ptr = ((Number) fieldValue).longValue();
        }
        if (ptr == 0L) {
            reportPeerDiagnostic(window, "peer=" + peer.getClass().getName()
                    + ", platform=" + platform.getClass().getName()
                    + (getNSWindowPtr == null ? " has no getNSWindowPtr()" : " returned NSWindow pointer=0"));
        } else {
            lastPeerDiagnostic = "";
        }
        return ptr;
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue up the peer hierarchy.
            } catch (Throwable inaccessible) {
                return null;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
        }
        return null;
    }

    static boolean isMac() { return MAC; }
}
