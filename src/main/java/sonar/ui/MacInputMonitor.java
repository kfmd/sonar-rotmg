package sonar.ui;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Passive macOS Quartz event tap. It observes key, wheel and click events, returns every
 * event unchanged, and never synthesizes input. macOS requires the user to grant Input
 * Monitoring permission to Sonar before this can work.
 */
final class MacInputMonitor {
    private MacInputMonitor() {}

    interface Listener {
        void wheel(int x, int y, int notches);
        void clicked(int x, int y);
    }

    private static final int EVENT_LEFT_DOWN = 1, EVENT_LEFT_UP = 2;
    private static final int EVENT_RIGHT_DOWN = 3, EVENT_RIGHT_UP = 4;
    private static final int EVENT_KEY_DOWN = 10, EVENT_KEY_UP = 11;
    private static final int EVENT_SCROLL = 22;
    private static final int EVENT_OTHER_DOWN = 25, EVENT_OTHER_UP = 26;
    private static final int EVENT_TAP_DISABLED_TIMEOUT = 0xFFFFFFFE;
    private static final int EVENT_TAP_DISABLED_USER = 0xFFFFFFFF;
    private static final int FIELD_KEYCODE = 9, FIELD_SCROLL_DELTA_Y = 11;
    private static final long EVENT_MASK = (1L << EVENT_LEFT_DOWN) | (1L << EVENT_LEFT_UP)
            | (1L << EVENT_RIGHT_DOWN) | (1L << EVENT_RIGHT_UP)
            | (1L << EVENT_KEY_DOWN) | (1L << EVENT_KEY_UP)
            | (1L << EVENT_SCROLL) | (1L << EVENT_OTHER_DOWN) | (1L << EVENT_OTHER_UP);

    private interface CoreGraphics extends Library {
        Pointer CGEventTapCreate(int tap, int place, int options, long mask, EventCallback callback, Pointer userInfo);
        void CGEventTapEnable(Pointer tap, boolean enable);
        long CGEventGetIntegerValueField(Pointer event, int field);
        CGPoint CGEventGetLocation(Pointer event);
    }

    @Structure.FieldOrder({"x", "y"})
    private static class CGPoint extends Structure implements Structure.ByValue {
        public double x, y;
        @Override protected java.util.List<String> getFieldOrder() { return Arrays.asList("x", "y"); }
    }

    private interface EventCallback extends Callback {
        Pointer invoke(Pointer proxy, int type, Pointer event, Pointer userInfo);
    }

    private interface CoreFoundation extends Library {
        Pointer CFMachPortCreateRunLoopSource(Pointer allocator, Pointer port, long order);
        void CFRunLoopAddSource(Pointer loop, Pointer source, Pointer mode);
        void CFRunLoopRun();
        Pointer CFRunLoopGetCurrent();
    }

    private static CoreGraphics cg;
    private static CoreFoundation cf;
    private static Pointer tap;
    private static EventCallback callback;
    private static volatile boolean started;
    private static volatile boolean failed;
    private static volatile Listener listener;
    private static final Set<Integer> keysDown = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> keysPressed = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> mouseDown = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> mousePressed = ConcurrentHashMap.newKeySet();
    private static final LinkedBlockingQueue<int[]> events = new LinkedBlockingQueue<>(256);
    private static Thread inputThread, dispatchThread;

    static synchronized void start() {
        if (started || failed || !MacOSNative.isMac()) return;
        started = true;
        try {
            cg = Native.load("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", CoreGraphics.class);
            cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation.class);
            inputThread = new Thread(MacInputMonitor::runTap, "sonar-macos-input-tap");
            inputThread.setDaemon(true);
            inputThread.start();
            dispatchThread = new Thread(MacInputMonitor::dispatch, "sonar-macos-input-dispatch");
            dispatchThread.setDaemon(true);
            dispatchThread.start();
        } catch (Throwable t) {
            failed = true;
            started = false;
            System.err.println("[mac] input monitoring unavailable: " + t);
        }
    }

    static void listen(Listener who) {
        listener = who;
        start();
    }

    static void stopListening() {
        listener = null;
        events.clear();
    }

    private static void runTap() {
        try {
            // Session event tap, head of chain, listen-only. It cannot modify or suppress input.
            callback = (proxy, type, event, userInfo) -> {
                try {
                    if (type == EVENT_TAP_DISABLED_TIMEOUT || type == EVENT_TAP_DISABLED_USER) {
                        if (tap != null) cg.CGEventTapEnable(tap, true);
                        return event;
                    }
                    if (type == EVENT_KEY_DOWN || type == EVENT_KEY_UP) {
                        int code = (int) cg.CGEventGetIntegerValueField(event, FIELD_KEYCODE);
                        if (type == EVENT_KEY_DOWN) {
                            if (keysDown.add(code)) keysPressed.add(code);
                        } else keysDown.remove(code);
                    } else if (type == EVENT_LEFT_DOWN || type == EVENT_RIGHT_DOWN || type == EVENT_OTHER_DOWN) {
                        int button = type == EVENT_LEFT_DOWN ? 0 : type == EVENT_RIGHT_DOWN ? 1 : 2;
                        if (mouseDown.add(button)) mousePressed.add(button);
                        if (type == EVENT_LEFT_DOWN) enqueue(event, 0);
                    } else if (type == EVENT_LEFT_UP || type == EVENT_RIGHT_UP || type == EVENT_OTHER_UP) {
                        mouseDown.remove(type == EVENT_LEFT_UP ? 0 : type == EVENT_RIGHT_UP ? 1 : 2);
                    } else if (type == EVENT_SCROLL) {
                        int notches = (int) cg.CGEventGetIntegerValueField(event, FIELD_SCROLL_DELTA_Y);
                        if (notches != 0) enqueue(event, notches);
                    }
                } catch (Throwable ignored) {
                    // Native callbacks must never throw across the C boundary.
                }
                return event;
            };
            // kCGSessionEventTap=1, kCGHeadInsertEventTap=0, kCGEventTapOptionListenOnly=1.
            tap = cg.CGEventTapCreate(1, 0, 1, EVENT_MASK, callback, null);
            if (tap == null) {
                failed = true;
                System.err.println("[mac] Input Monitoring permission is missing. Enable Sonar in System Settings > Privacy & Security > Input Monitoring, then restart Sonar.");
                return;
            }
            Pointer source = cf.CFMachPortCreateRunLoopSource(null, tap, 0);
            Pointer loop = cf.CFRunLoopGetCurrent();
            // kCFRunLoopCommonModes is the standard common-mode run-loop string.
            com.sun.jna.NativeLibrary cfLibrary = com.sun.jna.NativeLibrary.getInstance(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
            Pointer commonModes = cfLibrary.getGlobalVariableAddress("kCFRunLoopCommonModes").getPointer(0);
            cf.CFRunLoopAddSource(loop, source, commonModes);
            cf.CFRunLoopRun();
        } catch (Throwable t) {
            failed = true;
            System.err.println("[mac] the global input monitor could not start: " + t);
        }
    }

    private static void enqueue(Pointer event, int notches) {
        CGPoint point = cg.CGEventGetLocation(event);
        if (point == null) return;
        int x = (int) Math.round(point.x), y = (int) Math.round(point.y);
        events.offer(new int[]{x, y, notches});
    }

    private static void dispatch() {
        while (true) {
            try {
                int[] event = events.take();
                Listener who = listener;
                if (who == null) continue;
                if (event[2] == 0) who.clicked(event[0], event[1]);
                else who.wheel(event[0], event[1], event[2]);
            } catch (InterruptedException stop) {
                return;
            } catch (Throwable ignored) {
                // One failed UI callback must not terminate the listener.
            }
        }
    }

    static boolean keyDown(int macKeyCode) {
        return macKeyCode >= 0 && keysDown.contains(macKeyCode);
    }

    static boolean wasPressed(int macKeyCode) {
        return macKeyCode >= 0 && keysPressed.remove(macKeyCode);
    }

    static boolean mouseDown() {
        return !mouseDown.isEmpty();
    }

    static boolean mouseButtonDown(int button) {
        return mouseDown.contains(button);
    }

    static boolean wasMousePressed(int button) {
        return mousePressed.remove(button);
    }

    static boolean isAvailable() { return started && !failed && tap != null; }
}
