package sonar.ui;

/**
 * The game's own key bindings, read from where Exalt keeps them.
 * <p>
 * Exalt is a Unity game, so its settings live in the registry under
 * {@code HKCU\Software\DECA Live Operations GmbH\RotMGExalt} with a hash
 * suffix on every name. The minimap's zoom is not stored as a level anywhere
 * - only the camera's is - so there is no way to read what zoom the minimap
 * is currently at. The bindings, though, are right there, which is enough:
 * watch the same keys the game watches and the overlay moves when it does.
 * <p>
 * Reading the real binding rather than inventing one matters because it
 * follows a rebind. Someone who has moved minimap zoom onto the numpad gets
 * the numpad watched, without being told to.
 */
final class GameKeys {

    private GameKeys() {
    }

    private static final String KEY = "HKCU\\Software\\DECA Live Operations GmbH\\RotMGExalt";

    /** Windows virtual key codes for minimap zoom in and out; 0 where the binding could not be read. */
    private static volatile int[] zoomKeys;

    static int[] minimapZoom() {
        int[] got = zoomKeys;
        if (got != null) return got;
        int in = keyCodeOf(read("Key.MiniMapZoomIn"));
        int out = keyCodeOf(read("Key.MiniMapZoomOut"));
        // Unity's defaults are '=' and '-'. On macOS use the physical Cocoa key codes.
        if (in == 0) in = MacOSNative.isMac() ? 24 : 0xBB;
        if (out == 0) out = MacOSNative.isMac() ? 27 : 0xBD;
        zoomKeys = got = new int[]{in, out};
        return got;
    }

    /** Forgets what was read, so a rebind is picked up without a restart. */
    static void forget() {
        zoomKeys = null;
    }

    /** The value of a setting whose name carries Unity's hash suffix, or -1. */
    private static int read(String prefix) {
        if (MacOSNative.isMac()) return MacGameSettings.read(prefix, -1);
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", KEY);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            int found = -1;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.startsWith(prefix + "_h")) continue;
                // name    REG_DWORD    0x3d
                String[] bits = t.split("\\s+");
                if (bits.length < 3) continue;
                String v = bits[bits.length - 1];
                found = v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
                break;
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            p.destroy();
            return found;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Platform key code for a Unity binding (Cocoa hardware code on macOS, VK on Windows). */
    static int keyCodeOf(int unity) {
        return MacOSNative.isMac() ? macCodeOf(unity) : vkOf(unity);
    }

    /** Unity's printable KeyCodes mapped to macOS virtual hardware key codes. */
    static int macCodeOf(int unity) {
        if (unity >= 97 && unity <= 122) {
            switch (unity) {
                case 97: return 0;   // A
                case 98: return 11;  // B
                case 99: return 8;   // C
                case 100: return 2;  // D
                case 101: return 14; // E
                case 102: return 3;  // F
                case 103: return 5;  // G
                case 104: return 4;  // H
                case 105: return 22; // I
                case 106: return 38; // J
                case 107: return 40; // K
                case 108: return 37; // L
                case 109: return 46; // M
                case 110: return 45; // N
                case 111: return 31; // O
                case 112: return 35; // P
                case 113: return 12; // Q
                case 114: return 15; // R
                case 115: return 1;  // S
                case 116: return 17; // T
                case 117: return 32; // U
                case 118: return 9;  // V
                case 119: return 13; // W
                case 120: return 7;  // X
                case 121: return 16; // Y
                case 122: return 6;  // Z
            }
        }
        if (unity >= 48 && unity <= 57) {
            int[] codes = {29,18,19,20,21,23,22,26,28,25};
            return codes[unity - 48];
        }
        if (unity >= 256 && unity <= 265) {
            int[] codes = {82,83,84,85,86,87,88,89,91,92};
            return codes[unity - 256];
        }
        if (unity >= 282 && unity <= 293) {
            int[] codes = {122,120,99,118,96,97,98,100,101,109,103,111};
            return codes[unity - 282];
        }
        switch (unity) {
            case 61: return 24;  // =
            case 45: return 27;  // -
            case 44: return 43;  // ,
            case 46: return 47;  // .
            case 47: return 44;  // /
            case 59: return 41;  // ;
            case 91: return 33;  // [
            case 93: return 30;  // ]
            case 92: return 42;  // \\
            case 39: return 39;  // '
            case 96: return 50;  // `
            case 32: return 49;  // space
            case 9: return 48;   // tab
            case 27: return 53;  // escape
            case 13: return 36;  // return
            case 266: return 65; // keypad decimal
            case 267: return 75; // keypad divide
            case 268: return 67; // keypad multiply
            case 269: return 78; // keypad minus
            case 270: return 69; // keypad plus
            case 273: return 126; // up
            case 274: return 125; // down
            case 275: return 124; // right
            case 276: return 123; // left
            default: return 0;
        }
    }

    /** Converts the Windows VK fallbacks used by the existing camera code to macOS key codes. */
    static int fromVirtualKey(int vk) {
        if (!MacOSNative.isMac()) return vk;
        if (vk >= 0x41 && vk <= 0x5A) return macCodeOf(vk - 0x41 + 97);
        if (vk >= 0x30 && vk <= 0x39) return macCodeOf(vk);
        switch (vk) {
            case 0x0D: return 36;
            case 0x1B: return 53;
            case 0xBF: return 44;
            case 0xBB: return 24;
            case 0xBD: return 27;
            case 0x6B: return 69;
            case 0x6D: return 78;
            case 0x11: return 59;
            case 0x10: return 56;
            default: return 0;
        }
    }

    /**
     * Unity's KeyCode to the Windows virtual key of the same physical key.
     * <p>
     * Unity numbers the printable keys by their ASCII value, so the letters
     * and digits fall out arithmetically; the rest are listed.
     */
    static int vkOf(int unity) {
        if (unity < 0) return 0;
        if (unity >= 97 && unity <= 122) return unity - 97 + 0x41;      // a-z
        if (unity >= 48 && unity <= 57) return unity - 48 + 0x30;       // 0-9 along the top
        if (unity >= 256 && unity <= 265) return unity - 256 + 0x60;    // numpad 0-9
        if (unity >= 282 && unity <= 293) return unity - 282 + 0x70;    // F1-F12
        switch (unity) {
            case 61: return 0xBB;   // =
            case 45: return 0xBD;   // -
            case 44: return 0xBC;   // ,
            case 46: return 0xBE;   // .
            case 47: return 0xBF;   // /
            case 59: return 0xBA;   // ;
            case 91: return 0xDB;   // [
            case 93: return 0xDD;   // ]
            case 92: return 0xDC;   // \
            case 39: return 0xDE;   // '
            case 96: return 0xC0;   // `
            case 32: return 0x20;   // space
            case 9:  return 0x09;   // tab
            case 266: return 0xBE;  // keypad .
            case 267: return 0x6F;  // keypad /
            case 268: return 0x6A;  // keypad *
            case 269: return 0x6D;  // keypad -
            case 270: return 0x6B;  // keypad +
            case 273: return 0x26;  // up
            case 274: return 0x28;  // down
            case 275: return 0x27;  // right
            case 276: return 0x25;  // left
            default: return 0;
        }
    }
}
