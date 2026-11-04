package sonar.ui;

import com.sun.jna.Function;
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
    private static class CGRect extends Structure implements Structure.ByValue {
        public double x, y, width, height;
        CGRect() {}
        CGRect(double x, double y, double width, double height) {
            this.x = x; this.y = y; this.width = width; this.height = height;
        }
    }

    private static CG cg;
    private static CF cf;
    private static Throwable nativeLoadFailure;

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

    private static boolean isGame(String owner, String title) {
        String a = (owner + " " + title).toLowerCase(java.util.Locale.ROOT);
        return a.contains("rotmg") || a.contains("realm of the mad god");
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

    /** Returns the visible Exalt window, or null when the game is closed/minimized. */
    static GameWindow gameWindow() {
        if (!load()) return null;
        Pointer array = null;
        try {
            array = cg.CGWindowListCopyWindowInfo(CG_WINDOW_LIST_ON_SCREEN_ONLY, 0);
            if (array == null) return null;
            long count = cf.CFArrayGetCount(array);
            for (long i = 0; i < count; i++) {
                Pointer item = cf.CFArrayGetValueAtIndex(array, i);
                if (item == null) continue;
                int layer = (int) number(value(item, "kCGWindowLayer"), -1);
                if (layer != 0) continue;
                String owner = string(value(item, "kCGWindowOwnerName"));
                String title = string(value(item, "kCGWindowName"));
                if (!isGame(owner, title)) continue;
                int id = (int) number(value(item, "kCGWindowNumber"), 0);
                int pid = (int) number(value(item, "kCGWindowOwnerPID"), 0);
                Pointer bounds = value(item, "kCGWindowBounds");
                double x = number(value(bounds, "X"), Double.NaN);
                double y = number(value(bounds, "Y"), Double.NaN);
                double w = number(value(bounds, "Width"), 0);
                double h = number(value(bounds, "Height"), 0);
                if (id > 0 && w > 0 && h > 0 && !Double.isNaN(x) && !Double.isNaN(y)) {
                    return new GameWindow(id, pid, owner, title,
                            new Rectangle((int) Math.round(x), (int) Math.round(y),
                                    (int) Math.round(w), (int) Math.round(h)));
                }
            }
        } catch (Throwable t) {
            System.err.println("[mac] game-window lookup failed: " + t);
        } finally {
            if (array != null) cf.CFRelease(array);
        }
        return null;
    }

    /** True if the first non-Sonar app window on screen belongs to ROTMG Exalt. */
    static boolean gameInFront() {
        if (!load()) return false;
        Pointer array = null;
        try {
            array = cg.CGWindowListCopyWindowInfo(CG_WINDOW_LIST_ON_SCREEN_ONLY, 0);
            if (array == null) return false;
            long count = cf.CFArrayGetCount(array);
            for (long i = 0; i < count; i++) {
                Pointer item = cf.CFArrayGetValueAtIndex(array, i);
                if (item == null) continue;
                int layer = (int) number(value(item, "kCGWindowLayer"), -1);
                if (layer != 0) continue;
                String owner = string(value(item, "kCGWindowOwnerName"));
                int pid = (int) number(value(item, "kCGWindowOwnerPID"), 0);
                if (isSonarProcess(owner, pid)) continue;
                String title = string(value(item, "kCGWindowName"));
                return isGame(owner, title);
            }
        } catch (Throwable t) {
            System.err.println("[mac] foreground-window lookup failed: " + t);
        } finally {
            if (array != null) cf.CFRelease(array);
        }
        return false;
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
            if (image == null) return null;
            int iw = cg.CGImageGetWidth(image), ih = cg.CGImageGetHeight(image);
            long rowBytes = cg.CGImageGetBytesPerRow(image);
            if (iw <= 0 || ih <= 0 || rowBytes < (long) iw * 4) return null;
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
                for (int y = 0; y < outH; y++) {
                    long sourceRow = (long) (top + y) * rowBytes + (long) left * 4;
                    for (int x = 0; x < outW; x++) {
                        long p = sourceRow + (long) x * 4;
                        int c0 = bytes.getByte(p) & 255;
                        int c1 = bytes.getByte(p + 1) & 255;
                        int c2 = bytes.getByte(p + 2) & 255;
                        int c3 = bytes.getByte(p + 3) & 255;
                        int r, g, b;
                        if (bgra) { b = c0; g = c1; r = c2; }
                        else if (abgr) { r = c3; g = c2; b = c1; }
                        else if (argb) { r = c1; g = c2; b = c3; }
                        else if (rgba) { r = c0; g = c1; b = c2; }
                        else { b = c0; g = c1; r = c2; }
                        out.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
                return out;
            } finally {
                cf.CFRelease(copied);
            }
        } catch (Throwable t) {
            System.err.println("[mac] game-window capture failed: " + t
                    + ". Check System Settings > Privacy & Security > Screen & System Audio Recording.");
            return null;
        } finally {
            if (image != null) {
                try { cg.CGImageRelease(image); } catch (Throwable ignored) {}
            }
        }
    }

    /** Sets NSWindow.ignoresMouseEvents on a visible AWT window; requires the launcher module opens. */
    static boolean setClickThrough(Window window, boolean through) {
        if (!MAC || window == null) return false;
        try {
            long handle = nativeWindowPointer(window);
            if (handle == 0) return false;
            NativeLibrary objc = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib");
            Function msg = objc.getFunction("objc_msgSend");
            Function selector = objc.getFunction("sel_registerName");
            Pointer nativeWindow = new Pointer(handle);

            // Keep Sonar's overlay in the same Spaces as Exalt, including over a native
            // macOS full-screen Space. 1 = CanJoinAllSpaces; 256 = FullScreenAuxiliary.
            // This does not alter the game window or change input delivery.
            try {
                Pointer behavior = selector.invokePointer(new Object[]{"setCollectionBehavior:"});
                msg.invokeVoid(new Object[]{nativeWindow, behavior, Long.valueOf(257L)});
            } catch (Throwable unsupportedCollectionBehavior) {
                System.err.println("[mac] could not enable overlay across full-screen Spaces: "
                        + unsupportedCollectionBehavior);
            }

            Pointer sel = selector.invokePointer(new Object[]{"setIgnoresMouseEvents:"});
            msg.invokeVoid(new Object[]{nativeWindow, sel, (byte) (through ? 1 : 0)});
            Pointer getter = selector.invokePointer(new Object[]{"ignoresMouseEvents"});
            Object result = msg.invoke(Byte.class, new Object[]{nativeWindow, getter});
            byte actual = result instanceof Number ? ((Number) result).byteValue() : 0;
            return (actual != 0) == through;
        } catch (Throwable t) {
            System.err.println("[mac] could not change overlay mouse transparency: " + t);
            return false;
        }
    }

    private static long nativeWindowPointer(Window window) throws Exception {
        Class<?> accessorClass = Class.forName("sun.awt.AWTAccessor");
        Object componentAccessor = accessorClass.getMethod("getComponentAccessor").invoke(null);
        Class<?> componentAccessorType = Class.forName("sun.awt.AWTAccessor$ComponentAccessor");
        Method getPeer = componentAccessorType.getMethod("getPeer", java.awt.Component.class);
        Object peer = getPeer.invoke(componentAccessor, window);
        if (peer == null) return 0;
        Method getPlatformWindow = findMethod(peer.getClass(), "getPlatformWindow");
        if (getPlatformWindow == null) return 0;
        getPlatformWindow.setAccessible(true);
        Object platform = getPlatformWindow.invoke(peer);
        if (platform == null) return 0;
        Method getNSWindowPtr = findMethod(platform.getClass(), "getNSWindowPtr");
        if (getNSWindowPtr == null) return 0;
        getNSWindowPtr.setAccessible(true);
        Object value = getNSWindowPtr.invoke(platform);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == 0) return m;
        }
        return null;
    }

    static boolean isMac() { return MAC; }
}
