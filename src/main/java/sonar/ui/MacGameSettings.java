package sonar.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Unity PlayerPrefs from the Exalt macOS preferences plist (read-only). */
final class MacGameSettings {
    private MacGameSettings() {}

    private static final Pattern ENTRY = Pattern.compile("[\\\"]?([A-Za-z0-9._-]+)[\\\"]?\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|(-?\\d+)|0x([0-9a-fA-F]+)|(true|false))\\s*;?");
    private static volatile Map<String, Integer> cached;

    static int read(String prefix, int fallback) {
        Integer value = readAll().get(prefix);
        return value == null ? fallback : value;
    }

    static Map<String, Integer> readAll() {
        Map<String, Integer> ready = cached;
        if (ready != null) return ready;
        synchronized (MacGameSettings.class) {
            if (cached != null) return cached;
            Map<String, Integer> values = new HashMap<>();
            for (String domain : domains()) {
                String output = runDefaults(domain);
                Matcher matcher = ENTRY.matcher(output);
                while (matcher.find()) {
                    String name = matcher.group(1);
                    int hash = name.lastIndexOf("_h");
                    if (hash > 0) name = name.substring(0, hash);
                    String decimal = matcher.group(3), hex = matcher.group(4), bool = matcher.group(5);
                    try {
                        int number = decimal != null ? Integer.parseInt(decimal)
                                : hex != null ? (int) Long.parseLong(hex, 16)
                                : bool != null ? ("true".equalsIgnoreCase(bool) ? 1 : 0) : Integer.MIN_VALUE;
                        if (number != Integer.MIN_VALUE) values.put(name, number);
                    } catch (NumberFormatException ignored) { }
                }
            }
            cached = Collections.unmodifiableMap(values);
            if (values.isEmpty()) {
                System.out.println("[mac] Exalt preferences were not found. Sonar will use the game's default key bindings.");
            }
            return cached;
        }
    }

    private static String[] domains() {
        // Unity bundle IDs have varied between Exalt distributions. Discover likely domains
        // first, then try the commonly used DECA identifiers as fallbacks.
        String all = run("/usr/bin/defaults", "domains");
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (String item : all.split(",")) {
            String domain = item.trim();
            String lower = domain.toLowerCase(Locale.ROOT);
            if ((lower.contains("deca") || lower.contains("realm")) && lower.contains("rotmg")) found.add(domain);
        }
        found.add("com.DECA.LiveOperationsGmbH.RotMGExalt");
        found.add("com.deca.rotmgexalt");
        found.add("com.DECA.RotMGExalt");
        return found.toArray(new String[0]);
    }

    private static String runDefaults(String domain) {
        return run("/usr/bin/defaults", "read", domain);
    }

    private static String run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            process.waitFor(2, TimeUnit.SECONDS);
            return out.toString();
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return "";
        }
    }
}
