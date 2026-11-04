package sonar.assets;

import sonar.assets.resextractor.UnityExtractor;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import sonar.util.PropertiesManager;
import sonar.util.Util;

/**
 * Main loader for assets. If assets are missing or are outdated,
 * extracts the assets from realm resources files.
 */
public class AssetExtractor {

    public static final String ASSETS_OBJECT_FILE_DIR_PATH =
        "assets/ObjectID.list";
    public static final String ASSETS_TILE_FILE_DIR_PATH = "assets/TileID.list";
    private static final String XML_DIR_PATH = "assets/xml";
    private static final File[] ASSET_FOLDERS = {
        sonar.DataDir.file("assets/flatbuffer/"),
        sonar.DataDir.file("assets/sprites/"),
        sonar.DataDir.file("assets/xml/"),
    };
    private static String REALM_RES_PATH;
    private static JOptionPane pane;

    static {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            REALM_RES_PATH = macAssetsPath();
        } else if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                REALM_RES_PATH =
                        localAppData +
                                "/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";

                // Fallback for older installations
                File testFile = new File(REALM_RES_PATH);
                if (!testFile.exists()) {
                    REALM_RES_PATH =
                            "RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
                }
            }
        } else {
            REALM_RES_PATH =
                System.getenv("LOCALAPPDATA") +
                "/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
        }

        // A realm folder picked by the user in an earlier session wins over the defaults.
        String savedPath = PropertiesManager.getProperty("realmResPath");
        if (savedPath != null && new File(savedPath).isFile()) {
            REALM_RES_PATH = savedPath;
        }
    }


    /** Common Steam and standalone Unity app-bundle locations on macOS. */
    private static String macAssetsPath() {
        String home = System.getProperty("user.home", "");
        String[] candidates = {
                // Exalt Client Mac Production client used by the current macOS installation.
                home + "/.local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets",
                // Steam macOS installation using the launcher bundle name.
                home + "/Library/Application Support/Steam/steamapps/common/Realm of the Mad God/RotMG Exalt Launcher.app/Contents/Resources/Data/resources.assets",
                // Older/alternate Steam and standalone bundle layouts.
                home + "/Library/Application Support/Steam/steamapps/common/Realm of the Mad God Exalt/RotMG Exalt.app/Contents/Resources/Data/resources.assets",
                home + "/Library/Application Support/Steam/steamapps/common/Realm of the Mad God Exalt/RotMGExalt.app/Contents/Resources/Data/resources.assets",
                "/Applications/RotMG Exalt.app/Contents/Resources/Data/resources.assets",
                "/Applications/RotMGExalt.app/Contents/Resources/Data/resources.assets",
                "/Applications/Realm of the Mad God Exalt.app/Contents/Resources/Data/resources.assets",
                home + "/Library/Application Support/RealmOfTheMadGod/Production/RotMG Exalt.app/Contents/Resources/Data/resources.assets",
                home + "/Library/Application Support/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets"
        };
        for (String path : candidates) if (new File(path).isFile()) return path;

        // Spotlight can find app bundles in custom Steam libraries and non-standard locations.
        // This is only a convenience lookup; the existing file picker remains the final fallback.
        Process search = null;
        try {
            String query = "kMDItemFSName == 'RotMG Exalt Launcher.app' || "
                    + "kMDItemFSName == 'RotMG Exalt.app' || "
                    + "kMDItemFSName == 'RotMGExalt.app' || "
                    + "kMDItemFSName == 'Realm of the Mad God Exalt.app'";
            search = new ProcessBuilder("/usr/bin/mdfind", query).redirectErrorStream(true).start();
            if (!search.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                search.destroyForcibly();
            } else {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                        search.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String bundle;
                while ((bundle = reader.readLine()) != null) {
                    File assets = new File(bundle.trim(), "Contents/Resources/Data/resources.assets");
                    if (assets.isFile()) return assets.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
            if (search != null) search.destroyForcibly();
        }

        // Keep the most likely path so the existing asset picker can be used if Spotlight is
        // disabled or the launcher installed the game in an unindexed location.
        return candidates[0];
    }

    public static void main(String[] args) throws Throwable {
        //        checkForExtraction(Version.VERSION);
        pane = new JOptionPane();
        extractAssetsFromXML();
    }

    /**
     * Sets the resource path for finding where the game assets are found
     * @param path Path to the realm resource file.
     */
    public static void setRealmResPath(String path) {
        REALM_RES_PATH = path;
    }

    /**
     * Main loader for realm assets.
     */
    public static void checkForExtraction(String version) throws Throwable {
        String lastModifiedTime = lastEdited(version);
        if (checkUpdateAssets(lastModifiedTime) != 0) {
            assetExtractionWindow(lastModifiedTime);
        }
    }

    /**
     * Extracts the assets without any dialog windows, for use by setup scripts.
     *
     * @param version Version string of the app that later loads the assets.
     * @return True if the assets are extracted and up to date.
     */
    public static boolean extractHeadless(String version) throws Throwable {
        File assetsFile = assetFile();
        if (assetsFile == null) return false;
        File externalOutput = externalExaltOutput();
        boolean isMacOS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        if (isMacOS && externalOutput == null) {
            throw new IOException(
                    "The current macOS Exalt build needs the external Unity extraction output. "
                            + "Run rotmg-network/exalt-extractor against "
                            + "~/.local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data "
                            + "and set SONAR_EXALT_OUTPUT_DIR or -Dsonar.exalt.output to its output folder. "
                            + "A persistent default is ~/Library/Application Support/Sonar/exalt-extractor-output.");
        }

        String lastModifiedTime = lastEdited(version);
        if (checkUpdateAssets(lastModifiedTime) == 0) {
            System.out.println("Assets already up to date.");
            return true;
        }

        if (externalOutput != null) {
            System.out.println("[assets] Importing Exalt Extractor output from " + externalOutput.getAbsolutePath());
            importExternalExtraction(externalOutput);
            // UnityPy resolves this build's streamed Unity resources. Sonar then builds
            // its own compact object/tile indexes from the manifest-listed XML files.
            extractAssetsFromXML();
            extractArcaneArt(assetsFile);
        } else {
            extractAssets(assetsFile, lastModifiedTime);
            extractAssetsFromXML();
        }

        // Only mark the cache current after import and object/tile indexing succeed.
        PropertiesManager.setProperties("lastModifiedTime", lastModifiedTime);
        return checkUpdateAssets(lastModifiedTime) == 0;
    }

    public static String lastEdited(String version) throws IOException {
        File file = assetFile();
        if (file == null) return "missing-" + version;
        BasicFileAttributes attr = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        String signature = attr.lastModifiedTime().toString() + "-" + version;
        File externalOutput = externalExaltOutput();
        if (externalOutput != null) {
            long latest = 0L;
            long totalBytes = 0L;
            long fileCount = 0L;
            Path root = externalOutput.toPath();
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                    BasicFileAttributes externalAttr = Files.readAttributes(path, BasicFileAttributes.class);
                    latest = Math.max(latest, externalAttr.lastModifiedTime().toMillis());
                    totalBytes += externalAttr.size();
                    fileCount++;
                }
            }
            signature += "-external-" + root.toAbsolutePath().normalize() + "-" + latest + "-" + totalBytes + "-" + fileCount;
        }
        return signature;
    }

    /**
     * GUI dialog options for extracting the assets.
     *
     * @param lastModifiedTime Last modified time of the assets file.
     */
    private static void assetExtractionWindow(String lastModifiedTime)
        throws Throwable {
        JFrame frame = new JFrame("Realm Shark Asset Extractor");
        frame.setResizable(false);
        frame.setVisible(true);
        Object[] options = { "Extract", "Ignore" };
        int n = JOptionPane.showOptionDialog(
            frame,
            "New update available\n" + "Assets are needed for some features?",
            "Asset Extractor",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null, //do not use a custom Icon
            options, //the titles of buttons
            options[1]
        ); //default button title
        if (n == 0) {
            File assetsFile = getAssetsFile();
            if (assetsFile != null) {
                waitWhileExtracting(frame, assetsFile, lastModifiedTime);
            }
        }
        frame.dispose();
    }

    /**
     * Gets the asset file and if file is not found in default folder
     * opens a file dialog window for user to give location of assets file.
     *
     * @return The resources.assets file used for extraction
     */
    private static File getAssetsFile()
        throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        File f = assetFile();

        if (f == null || !f.exists()) {
            int i = JOptionPane.showOptionDialog(
                null,
                "Please select realm folder",
                "Realm folder not found",
                JOptionPane.ERROR_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                null,
                new Object[] { "Realm Folder", "Cancel" },
                null
            );
            if (i == 0) {
                UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName()
                );

                File path =
                    FileSystemView.getFileSystemView().getDefaultDirectory();
                while (true) {
                    JFileChooser fc = new JFileChooser();
                    fc.setCurrentDirectory(path);
                    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    int returnVal = fc.showDialog(null, "Realm Folder");
                    if (returnVal == JFileChooser.APPROVE_OPTION) {
                        path = fc.getSelectedFile();

                        try (
                            Stream<Path> pathStream = Files.find(
                                path.toPath(),
                                5,
                                (p, basicFileAttributes) ->
                                    p
                                        .getFileName()
                                        .toString()
                                        .equalsIgnoreCase("resources.assets")
                            )
                        ) {
                            List<Path> list = pathStream.collect(
                                Collectors.toList()
                            );
                            if (list.size() == 1) {
                                Path p = list.get(0);
                                f = p.toFile();
                                PropertiesManager.setProperties(
                                    "realmResPath",
                                    f.getPath()
                                );
                                break;
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else if (returnVal == JFileChooser.CANCEL_OPTION) {
                        return null;
                    }
                }
            }
        }
        return f;
    }

    /**
     * Starts the extraction on the resources.assets file while
     * creates dialog window for user to wait.
     *
     * @param frame
     * @param assetsFile       The resources.assets file to be extracted.
     * @param lastModifiedTime Last modified time used to keep track of updates on the assets file.
     */
    private static void waitWhileExtracting(
        JFrame frame,
        File assetsFile,
        String lastModifiedTime
    ) throws Throwable {
        JPanel panel = new JPanel(new BorderLayout());
        JButton ok = new JButton("OK");
        ok.setEnabled(false);
        JLabel text = new JLabel("Please wait while extracting.");
        panel.add(text, BorderLayout.CENTER);
        ok.addActionListener(e -> {
            Component component = (Component) e.getSource();
            if (component == null) {
                return;
            }
            Window win = SwingUtilities.getWindowAncestor(component);
            if (win == null) {
                return;
            }
            win.dispose();
        });
        pane = new JOptionPane(
            "Extracting. Please wait.",
            JOptionPane.PLAIN_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
            null,
            new JButton[] { ok },
            ok
        );
        JDialog dialog = pane.createDialog(frame, "Extracting");
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        Thread extractThread = new Thread(() -> {
            try {
                extractAssets(assetsFile, lastModifiedTime);
                extractAssetsFromXML();
            } catch (IOException | ParserConfigurationException e) {
                throw new RuntimeException(e);
            }
            pane.setMessage("Finished extraction.");
            ok.setEnabled(true);
            System.out.println("done extracting.");
            dialog.dispose();
        });
        AtomicReference<Throwable> throwableReference = new AtomicReference<>();
        extractThread.setUncaughtExceptionHandler((t, e) -> {
            dialog.dispose();
            throwableReference.set(e);
        });

        extractThread.start();
        dialog.setVisible(true);

        extractThread.join();
        Throwable throwable = throwableReference.get();
        if (throwable == null) return;
        if (throwable instanceof AccessDeniedException) {
            JOptionPane.showMessageDialog(
                pane,
                "<html>Extraction access denied, failed to extract!<br/>Please move Sonar to a different folder,<br/>Windows is blocking access in current folder.</html>\""
            );
            System.exit(0);
        }
        throw throwable;
    }

    public static void setDisplay(String s) {
        if (pane == null) {
            System.out.println(s);
            return;
        }
        pane.setMessage("<html>Extracting. Please wait.<br/>" + s + "</html>");
    }

    /**
     * Uses custom path to resources.assets file. If no custom path is found
     * finds the platform-specific Realm resources.assets file.
     *
     * @return Absolute path to resources.assets file.
     */
    public static File assetFile() {
        File defaultFile;
        try {
            if (Paths.get(REALM_RES_PATH).isAbsolute()) {
                defaultFile = new File(REALM_RES_PATH);
            } else {
                String homeDir = FileSystemView.getFileSystemView().getDefaultDirectory().getAbsolutePath();
                Path defaultPath = Paths.get(homeDir, REALM_RES_PATH);
                defaultFile = defaultPath.toFile();
            }
        } catch (java.nio.file.InvalidPathException e) {
            System.err.println(
                "ERROR: Invalid path format in REALM_RES_PATH: " +
                    REALM_RES_PATH
            );
            return null;
        }
        if (defaultFile.exists()) {
            System.out.println("Using path: " + defaultFile.getAbsolutePath());
            return defaultFile;
        }
        System.err.println(
            "ERROR: Default path not found: " + defaultFile.getAbsolutePath()
        );
        return null;
    }

    /**
     * Resolve the Exalt Extractor output from a JVM property, environment variable,
     * or the stable per-user location used by the macOS app bundle.
     */
    private static File externalExaltOutput() {
        String configured = System.getProperty("sonar.exalt.output");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("SONAR_EXALT_OUTPUT_DIR");
        }
        if (configured == null || configured.trim().isEmpty()) {
            File defaultOutput = sonar.DataDir.file("exalt-extractor-output");
            return isExternalExaltOutput(defaultOutput) ? defaultOutput : null;
        }

        File root = new File(configured.trim()).getAbsoluteFile();
        if (!isExternalExaltOutput(root)) {
            throw new IllegalArgumentException(
                    "Invalid Exalt Extractor output directory: " + root.getAbsolutePath()
                            + ". Expected xml/, spritesheets/spritesheet.json, and assets_manifest.json.");
        }
        return root;
    }

    private static boolean isExternalExaltOutput(File root) {
        return root != null
                && root.isDirectory()
                && new File(root, "xml").isDirectory()
                && new File(root, "spritesheets/spritesheet.json").isFile()
                && new File(root, "assets_manifest.json").isFile();
    }

    /**
     * Import XML definitions and sprite assets exported by rotmg-network/exalt-extractor.
     * The original game installation and extractor output are only read, never modified.
     */
    private static void importExternalExtraction(File sourceRoot) throws IOException {
        Path sourceXml = new File(sourceRoot, "xml").toPath();
        Path targetXml = sonar.DataDir.file(XML_DIR_PATH).toPath();
        Path sourceSprites = new File(sourceRoot, "spritesheets").toPath();
        Path targetSprites = sonar.DataDir.file("assets/sprites").toPath();
        Path targetJson = sonar.DataDir.file("assets/json").toPath();

        Files.createDirectories(targetXml);
        Files.createDirectories(targetSprites);
        Files.createDirectories(targetJson);

        try (Stream<Path> paths = Files.list(targetXml)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)) Files.deleteIfExists(path);
            }
        }
        try (Stream<Path> paths = Files.list(targetSprites)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isRegularFile(path)
                        && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                    Files.deleteIfExists(path);
                }
            }
        }

        int xmlCount = 0;
        try (Stream<Path> paths = Files.walk(sourceXml)) {
            for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) continue;
                Files.copy(path, targetXml.resolve(path.getFileName().toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                xmlCount++;
            }
        }
        if (xmlCount == 0) throw new IOException("No XML files found in external Exalt extraction: " + sourceXml);

        for (String name : new String[]{"groundTiles.png", "characters.png", "characters_masks.png", "mapObjects.png"}) {
            Path from = sourceSprites.resolve(name);
            if (!Files.isRegularFile(from)) throw new IOException("Missing extracted sprite sheet: " + from);
            Files.copy(from, targetSprites.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(sourceSprites.resolve("spritesheet.json"), targetJson.resolve("spritesheet.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(new File(sourceRoot, "assets_manifest.json").toPath(), targetXml.resolve("manifest.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Keep packet-name lists as reference material. Sonar's own Java packet decoders
        // remain authoritative; names alone do not define packet serialization.
        File sourcePackets = new File(sourceRoot, "packets");
        if (sourcePackets.isDirectory()) {
            Path targetPackets = sonar.DataDir.dir("assets/packets").toPath();
            try (Stream<Path> paths = Files.list(sourcePackets.toPath())) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    if (Files.isRegularFile(path)) {
                        Files.copy(path, targetPackets.resolve(path.getFileName().toString()),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        System.out.printf("[assets] Imported %d XML files and 4 sprite sheets from Exalt Extractor output.%n", xmlCount);
    }

    /**
     * Extracts assets using the UnityExtractor from realms resrouces.assets file into assets folder.
     *
     * @param file             File path to resrouces.assets.
     * @param lastModifiedTime Last modified time of the assets file.
     */
    private static void extractAssets(File file, String lastModifiedTime)
        throws IOException {
        new UnityExtractor().extract(file, ASSET_FOLDERS);
        extractArcaneArt(file);
        PropertiesManager.setProperties("lastModifiedTime", lastModifiedTime);
    }

    /**
     * Pulls the Arcane Style effect art, which lives in a different assets file from
     * everything above. Nothing else depends on it, so a failure here is reported and
     * stepped over rather than losing the whole extraction: characters simply draw
     * without their styles, exactly as they did before this existed.
     */
    private static void extractArcaneArt(File resourcesAssets) {
        if (!sonar.ui.ArcaneArt.enabled()) return;
        try {
            File shared = sonar.assets.resextractor.ArcaneExtractor.sharedAssets(resourcesAssets);
            if (shared == null) {
                System.out.println("Arcane Style art: sharedassets1.assets not found; skipped.");
                return;
            }
            setDisplay("Extracting Arcane Style art");
            int n = sonar.assets.resextractor.ArcaneExtractor
                .extract(shared, sonar.DataDir.dir(sonar.assets.resextractor.ArcaneExtractor.DIR))
                .size();
            System.out.println("Arcane Style art: " + n + " effects extracted.");
        } catch (Throwable t) {
            System.out.println("Arcane Style art: not extracted (" + t + ")");
        }
    }

    /**
     * Checks if asset folders exist and current build version is matching.
     *
     * @param lastModifiedTime Last modified time of the assets file.
     * @return True if assets are missing.
     */
    private static int checkUpdateAssets(String lastModifiedTime) {
        if (!sonar.DataDir.file(ASSETS_OBJECT_FILE_DIR_PATH).exists()) return 1;
        if (!sonar.DataDir.file(ASSETS_TILE_FILE_DIR_PATH).exists()) return 2;
        if (
            !Objects.equals(
                PropertiesManager.getProperty("lastModifiedTime"),
                lastModifiedTime
            )
        ) return 3;
        return 0;
    }

    /**
     * Extracts assets from XML files.
     */
    private static void extractAssetsFromXML()
        throws IOException, ParserConfigurationException {
        ArrayList<AssetObject> objectAssets = new ArrayList<>();
        ArrayList<AssetTile> tileAssets = new ArrayList<>();
        ArrayList<Path> files = new ArrayList<>();
        Path xmlRoot = sonar.DataDir.file(XML_DIR_PATH).toPath();

        if (!Files.isDirectory(xmlRoot)) {
            throw new IOException("XML asset directory does not exist: " + xmlRoot);
        }

        // Close the walk stream deterministically. The Exalt manifest's object/tile
        // order is authoritative because legacy and current XML files can reuse IDs.
        try (Stream<Path> paths = Files.walk(xmlRoot)) {
            paths
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
                .sorted()
                .forEach(files::add);
        }
        files = orderXmlFilesByManifest(xmlRoot, files);

        System.out.println("Found " + files.size() + " definition XML files in " + xmlRoot.toAbsolutePath());
        int counter = 0;
        for (Path p : files) {
            counter++;
            long started = System.nanoTime();
            long size = Files.size(p);
            String fileLabel = xmlRoot.relativize(p).toString();
            AssetExtractor.setDisplay("Parsing XML Files " + counter + "/" + files.size());
            System.out.printf(
                "[assets] Parsing XML %d/%d: %s (%.1f KB)%n",
                counter, files.size(), fileLabel, size / 1024.0
            );
            try {
                parseXML(p, objectAssets, tileAssets);
                long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started
                );
                System.out.printf(
                    "[assets] Parsed %s in %d ms; objects=%d, tiles=%d%n",
                    fileLabel, elapsedMs, objectAssets.size(), tileAssets.size()
                );
            } catch (SAXException e) {
                System.err.printf("[assets] Skipping malformed XML %s: %s%n", fileLabel, e.getMessage());
            }
        }

        if (objectAssets.isEmpty() || tileAssets.isEmpty()) {
            throw new IOException(
                "Incomplete ROTMG XML asset extraction: found " + objectAssets.size()
                    + " objects and " + tileAssets.size() + " tiles. "
                    + "The asset cache was not marked current; inspect the extracted XML manifest and files."
            );
        }

        objectAssets.sort(Comparator.comparing(a -> a.id));
        objectAssets.forEach(e ->
            Util.print(ASSETS_OBJECT_FILE_DIR_PATH + "-", e.toString())
        );

        tileAssets.sort(Comparator.comparing(a -> a.id));
        tileAssets.forEach(e ->
            Util.print(ASSETS_TILE_FILE_DIR_PATH + "-", e.toString())
        );
        System.out.println(
            "[assets] XML parsing complete: " + objectAssets.size()
                + " objects, " + tileAssets.size() + " tiles."
        );
    }

    /**
     * Use only XML documents listed in the manifest's objects/tiles arrays, in that order.
     * Other TextAssets (for example pet behaviour metadata) can contain IDs that collide
     * with actual game objects and must not be added to ObjectID.list or TileID.list.
     * Extractor output is flattened, so nested manifest paths are matched by basename.
     */
    private static ArrayList<Path> orderXmlFilesByManifest(Path xmlRoot, ArrayList<Path> sortedFiles)
            throws IOException {
        Path manifestPath = xmlRoot.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            System.out.println("[assets] No XML ordering manifest found; using filename order.");
            return sortedFiles;
        }

        java.util.LinkedHashSet<Path> ordered = new java.util.LinkedHashSet<>();
        ArrayList<String> missing = new ArrayList<>();
        try (java.io.Reader reader = Files.newBufferedReader(manifestPath, java.nio.charset.StandardCharsets.UTF_8)) {
            com.google.gson.JsonObject manifest = new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
            for (String section : new String[]{"objects", "tiles"}) {
                com.google.gson.JsonArray entries = manifest.getAsJsonArray(section);
                if (entries == null) {
                    throw new IOException("XML manifest is missing the '" + section + "' list: " + manifestPath);
                }
                for (com.google.gson.JsonElement entry : entries) {
                    if (!entry.isJsonObject() || !entry.getAsJsonObject().has("path")) continue;
                    String relative = entry.getAsJsonObject().get("path").getAsString();
                    Path exact = xmlRoot.resolve(relative).normalize();
                    if (Files.isRegularFile(exact) && exact.startsWith(xmlRoot)) {
                        ordered.add(exact);
                        continue;
                    }
                    String basename = new File(relative).getName();
                    Path match = null;
                    for (Path candidate : sortedFiles) {
                        if (candidate.getFileName().toString().equalsIgnoreCase(basename)) {
                            match = candidate;
                            break;
                        }
                    }
                    if (match == null) missing.add(relative);
                    else ordered.add(match);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read XML ordering manifest " + manifestPath + ": " + e.getMessage(), e);
        }

        if (!missing.isEmpty()) {
            throw new IOException("The Exalt XML manifest references " + missing.size()
                    + " XML files that were not extracted. First missing entries: "
                    + missing.subList(0, Math.min(10, missing.size())));
        }
        if (ordered.isEmpty()) {
            throw new IOException("The Exalt XML manifest did not identify any Object/Ground XML files: " + manifestPath);
        }
        System.out.printf("[assets] Applied Exalt manifest precedence: %d definition XML files (objects then tiles).%n",
                ordered.size());
        return new ArrayList<>(ordered);
    }

    /**
     * Reloads assets to reset assets in running app.
     */
    private static void reloadAssetsOnRunningApp() {
        ImageBuffer.clear();
        IdToAsset.reloadAssets();
        SpriteJson.jsonFileReader();
    }

    /**
     * Creates a Document object from XML files given by path.
     *
     * @param path Path to a XML file.
     * @return Returns Document object.
     */
    private static Document getDocumentElement(Path path)
        throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // Unity's game XML does not need network DTDs or external entities. Besides
        // being safer, disabling them prevents a parser from stalling on an external
        // resource while the asset extraction progress appears frozen.
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(new IgnoreErrorHandler());
        db.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        Document doc = db.parse(new File(path.toAbsolutePath().toString()));
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Root method for parsing XML files.
     *
     * @param path         Path to an XML file.
     * @param objectAssets Object assets array to store parsed XML objects into.
     * @param tileAssets   Tile asset array to st store parsed XML tiles into.
     */
    private static void parseXML(
        Path path,
        ArrayList<AssetObject> objectAssets,
        ArrayList<AssetTile> tileAssets
    ) throws ParserConfigurationException, IOException, SAXException {
        Document doc = getDocumentElement(path);

        NodeList listObjects = doc.getElementsByTagName("Object");
        for (int i = 0; i < listObjects.getLength(); i++) {
            Node node = listObjects.item(i);
            AssetObject ao = new AssetObject();

            if (node.hasAttributes()) {
                addAttributes(node, ao);
            }

            parseChildObjects(node, ao);
            objectAssets.add(ao);
        }

        NodeList listTiles = doc.getElementsByTagName("Ground");
        for (int i = 0; i < listTiles.getLength(); i++) {
            Node node = listTiles.item(i);
            AssetTile at = new AssetTile();

            if (node.hasAttributes()) {
                addAttributes(node, at);
            }

            parseChildTiles(node, at);
            tileAssets.add(at);
        }
    }

    /**
     * Parses attributes from XML objects
     *
     * @param node Node of parse attributes
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addAttributes(Node node, Asset ao) {
        NamedNodeMap attribNode = node.getAttributes();
        for (int j = 0; j < attribNode.getLength(); j++) {
            Node item = attribNode.item(j);
            String name = item.getNodeName();
            String value = item.getNodeValue();

            if (name.equals("id")) {
                ao.idName = value;
            } else if (name.equals("type")) {
                ao.id = Integer.decode(value);
            }
        }
    }

    /**
     * Node parsing method to iterate over the sibling nodes and parse using lambdas.
     *
     * @param node The node to be parsed.
     * @param al   Asset lambda object for pasing the node data.
     */
    public static void addNode(Node node, AssetLambda al) {
        Node n = node.getFirstChild();
        while (n != null) {
            String name = n.getNodeName();
            String value = "";
            if (n.hasChildNodes()) value = n.getFirstChild().getNodeValue();

            al.parse(name, value, n);

            n = n.getNextSibling();
        }
    }

    /**
     * Child nodes of object data to parse.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void parseChildObjects(Node node, AssetObject ao) {
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "DisplayId":
                    ao.display = value;
                    break;
                case "Class":
                    ao.clazz = value;
                    break;
                case "Group":
                    ao.group = value;
                    break;
                case "Labels":
                    ao.labels = value;
                    break;
                case "Tier":
                    ao.tier = value;
                    break;
                case "SlotType":
                    ao.slotType = value;
                    break;
                case "Subattack":
                    addSubattack(n, ao);
                    break;
                case "Projectile":
                    addProjectile(n, ao);
                    break;
                case "Texture":
                case "AnimatedTexture":
                    addTexture(n, ao);
                    break;
                case "RandomTexture":
                    // The game picks one of several at random. The first stands in for all of
                    // them: without it an object like the Shatters' Derelict Monument had no
                    // texture at all, and every picture of it - the overlay's sprite included -
                    // came out empty.
                    if (ao.textures == null || ao.textures.isEmpty()) {
                        org.w3c.dom.NodeList kids = n.getChildNodes();
                        for (int k = 0; k < kids.getLength(); k++) {
                            Node kid = kids.item(k);
                            if ("Texture".equals(kid.getNodeName())) {
                                addTexture(kid, ao);
                                break;
                            }
                        }
                    }
                    break;
            }
        });
    }

    /**
     * Child nodes of tile data to parse.
     *
     * @param node The node to be parsed.
     * @param at   Asset objects to store the parsed data into.
     */
    private static void parseChildTiles(Node node, AssetTile at) {
        AssetDamage damage = new AssetDamage();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Texture":
                    addTexture(n, at);
                    break;
                case "RandomTexture":
                    if (n.hasChildNodes()) {
                        parseChildTiles(n, at);
                    }
                    break;
                case "MinDamage":
                    if (n.hasChildNodes()) {
                        damage.min = value;
                    }
                case "MaxDamage":
                    if (n.hasChildNodes()) {
                        damage.max = value;
                    }
                    break;
            }
        });
        if (damage.min != null && damage.max != null) {
            at.damage = damage;
        }
    }

    /**
     * Subattack to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addSubattack(Node node, AssetObject ao) {
        NamedNodeMap attribNode = node.getAttributes();
        for (int j = 0; j < attribNode.getLength(); j++) {
            Node item = attribNode.item(j);
            String name = item.getNodeName();
            String value = item.getNodeValue();

            if (name.equals("projectileId")) {
                int i = Integer.parseInt(value);
                ao.subattack.add(i);
            }
        }
    }

    /**
     * Projectile nodes of the main object data to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addProjectile(Node node, AssetObject ao) {
        AssetProjectile projectile = new AssetProjectile();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Damage":
                    projectile.min = value;
                    projectile.max = value;
                    break;
                case "MinDamage":
                    projectile.min = value;
                    break;
                case "MaxDamage":
                    projectile.max = value;
                    break;
                case "ArmorPiercing":
                    projectile.peirce = true;
                    break;
            }
        });
        if (projectile.min != null && projectile.max != null) {
            if (ao.projectiles == null) ao.projectiles = new ArrayList<>();
            ao.projectiles.add(projectile);
        }
    }

    /**
     * Texture nodes of the main object data to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addTexture(Node node, Asset ao) {
        AssetTexture texture = new AssetTexture();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Index":
                    texture.index = value.startsWith("0x")
                        ? Integer.decode(value)
                        : Integer.parseInt(value);
                    break;
                case "File":
                    texture.file = value;
                    break;
            }
        });
        if (texture.file != null && texture.index != -1) {
            if (ao.textures == null) ao.textures = new ArrayList<>();
            ao.textures.add(texture);
        }
    }

    /**
     * Lambda function used to parse the XML data.
     */
    private interface AssetLambda {
        void parse(String name, String a, Node n);
    }

    /**
     * Superclass to store XML parsed object and tile data into.
     */
    private static class Asset {

        int id;
        String idName = "";

        ArrayList<AssetTexture> textures;
    }

    /**
     * Class to store XML parsed object data into.
     */
    private static class AssetObject extends Asset {

        String display = "";
        String clazz = "";
        String group = "";
        String labels = "";
        String tier = "";
        String slotType = "";

        ArrayList<AssetProjectile> projectiles;
        ArrayList<Integer> subattack = new ArrayList<>();

        @Override
        public String toString() {
            //            if (clazz != null && clazz.equals("Equipment") && labels.contains("UT")) {
            //                idName = "UT " + idName;
            //            } else if (clazz != null && clazz.equals("Equipment") && !tier.equals("")) {
            //                idName = "T" + tier + " " + idName;
            //            }

            StringBuilder projectileString = new StringBuilder();
            if (projectiles != null) {
                projectileString.append(slotType + ",");
                if (subattack.size() == 0) {
                    for (AssetProjectile p : projectiles) {
                        projectileString.append(p);
                    }
                } else {
                    for (int i : subattack) {
                        projectileString.append(projectiles.get(i));
                    }
                }
                projectileString.deleteCharAt(projectileString.length() - 1);
            }

            StringBuilder textureString = new StringBuilder();
            if (textures != null) {
                for (AssetTexture p : textures) {
                    textureString.append(p);
                }
                textureString.deleteCharAt(textureString.length() - 1);
            }

            return String.format(
                "%s;%s;%s;%s;%s;%s;%s;%s",
                id,
                display,
                clazz,
                group,
                projectileString,
                textureString,
                labels,
                idName
            );
        }
    }

    /**
     * Class to store XML parsed tile data into.
     */
    private static class AssetTile extends Asset {

        AssetDamage damage;

        @Override
        public String toString() {
            StringBuilder damageString = new StringBuilder();
            if (damage != null) {
                damageString.append(damage.min).append(",").append(damage.max);
            }

            StringBuilder textureString = new StringBuilder();
            if (textures != null) {
                for (AssetTexture p : textures) {
                    textureString.append(p);
                }
                textureString.deleteCharAt(textureString.length() - 1);
            }
            return String.format(
                "%d;%s;%s;%s",
                id,
                textureString,
                damageString,
                idName
            );
        }
    }

    /**
     * Class to store XML parsed projectile data into.
     */
    private static class AssetProjectile {

        String min;
        String max;
        boolean peirce = false;

        @Override
        public String toString() {
            return (
                min.replaceAll("\t", "") +
                "," +
                max.replaceAll("\t", "") +
                "," +
                (peirce ? "1," : "0,")
            );
        }
    }

    /**
     * Class to store XML parsed damage data into.
     */
    private static class AssetDamage {

        String min;
        String max;

        @Override
        public String toString() {
            return min + "," + max;
        }
    }

    /**
     * Class to store XML parsed texture/sprite data into.
     */
    private static class AssetTexture {

        int index = -1;
        String file;

        @Override
        public String toString() {
            return index + "," + file + ",";
        }
    }

    private static class IgnoreErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) throws SAXException {
            System.err.println("[assets] XML warning: " + exception.getMessage());
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception)
            throws SAXException {
            throw exception;
        }
    }
}
