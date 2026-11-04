package sonar;

import java.awt.*;
import javax.swing.*;
import sonar.packets.data.QuestData;
import sonar.Sonar;
import sonar.backend.data.GameData;
import sonar.ui.SonarShell;

/**
 * Owns the application window.
 * <p>
 * This was Sonar's whole interface: a tab strip with Chat, Security, Characters,
 * Statistics, My Info and the rest hanging off it. Sonar draws all of that itself,
 * so what is left here is the frame and the two relays the backend still reaches
 * for - quest data arriving, and the sniffer starting or stopping.
 */
public class AppWindow {

    private static final int windowWidth = 500;
    private static final int windowHeight = 500;
    private static JFrame frame;
    private static GameData data;

    public AppWindow(GameData data) {
        AppWindow.data = data;
    }

    /** Builds the Sonar shell and puts it in the window. Swing components are created on the EDT. */
    public void create() {
        // Chat pings only load preferences and a local block list; keep that I/O off the EDT.
        sonar.ChatPings.start(data);
        Runnable createUi = () -> {
            System.out.println("[window] EDT UI construction started");
            SonarShell shell = new SonarShell(data);
            System.out.println("[window] SonarShell constructed");
            makeFrame(shell);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            createUi.run();
            return;
        }

        // This is a standalone Swing application, not an SWT/LWJGL embedded app.
        // Do not launch the JVM with -XstartOnFirstThread: that option puts macOS
        // AWT into embedded mode and disables AWT's own AppKit event loop unless
        // another native toolkit starts it. With the normal macOS Java launcher,
        // invokeAndWait is safe and ensures the frame is fully created before
        // Sonar continues bootstrapping its controllers and saved data.
        try {
            SwingUtilities.invokeAndWait(createUi);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the Sonar window", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Failed while creating the Sonar window", cause);
        }
    }

    private static void logFrameState(String stage) {
        JFrame current = frame;
        if (current == null) {
            System.out.println("[window] " + stage + ": JFrame is null");
            return;
        }
        Rectangle bounds = current.getBounds();
        GraphicsConfiguration gc = current.getGraphicsConfiguration();
        System.out.println("[window] " + stage
                + ": visible=" + current.isVisible()
                + ", showing=" + current.isShowing()
                + ", displayable=" + current.isDisplayable()
                + ", bounds=" + bounds
                + ", extendedState=" + current.getExtendedState()
                + ", graphicsConfiguration=" + (gc == null ? "null" : gc.getDevice().getIDstring()));
    }

    private void makeFrame(SonarShell shell) {
        System.out.println("[window] constructing JFrame");
        frame = new JFrame("Sonar " + SonarShell.version());
        logFrameState("JFrame constructed");
        // Every size, not one. A lone 256px image leaves Windows to scale it down to 24 for the
        // taskbar itself, and it does that poorly - the mark came out soft however crisply it was
        // drawn. Given the set, Windows picks the exact match and scales nothing.
        java.util.List<Image> icons = new java.util.ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128}) {
            java.net.URL u = Sonar.class.getResource("/icon/sonar" + s + ".png");
            if (u != null) icons.add(Toolkit.getDefaultToolkit().getImage(u));
        }
        if (!icons.isEmpty()) frame.setIconImages(icons);
        // Sonar draws its own header, so the grey system bar above it was a second title bar in a
        // palette that did not match. The switch is guarded: if a platform refuses an undecorated
        // window the app still opens, with the system bar it has always had.
        if (!"false".equalsIgnoreCase(System.getProperty("sonar.borderless", "true"))) {
            try {
                frame.setUndecorated(true);
                frame.getRootPane().setWindowDecorationStyle(javax.swing.JRootPane.NONE);
            } catch (RuntimeException ignored) {
                // stays decorated
            }
        }
        frame.setSize(windowWidth, windowHeight);
        logFrameState("after initial size");
        SonarShell.sizeFrame(frame);
        logFrameState("after SonarShell.sizeFrame");
        placeFrame();
        logFrameState("after placeFrame");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setContentPane(shell);
        logFrameState("after setContentPane");
        // a picture taken for a page or a test must never take the keyboard from a game being played
        if (System.getProperty("sonar.shot") != null) frame.setAutoRequestFocus(false);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                logFrameState("windowOpened event");
            }
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                logFrameState("windowClosed event");
            }
        });
        System.out.println("[window] calling setVisible(true)");
        frame.setVisible(true);
        frame.validate();
        frame.repaint();
        logFrameState("immediately after setVisible");
        // macOS can defer native window realization until the next AppKit/EDT turn.
        javax.swing.SwingUtilities.invokeLater(() -> {
            logFrameState("next EDT turn");
            if (frame != null && (!frame.isShowing() || frame.getWidth() < 100 || frame.getHeight() < 100)) {
                System.err.println("[window] WARNING: Sonar frame did not become a normal visible window; retrying size/show.");
                frame.setSize(Math.max(900, frame.getWidth()), Math.max(650, frame.getHeight()));
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                frame.toFront();
                frame.requestFocus();
                logFrameState("after visibility retry");
            } else if (frame != null) {
                frame.toFront();
            }
            // no dialog of its own: it checks the Start menu on a thread and says nothing
            Shortcuts.ensure();
            FirstRun.checkNpcap(frame);
        });
    }

    /**
     * Puts the window back where it was left.
     * <p>
     * It used to centre on the primary display every single launch, because only the
     * window's size was remembered and never its position. On one monitor that is
     * invisible. On two it means the window cannot be kept anywhere: you move it to the
     * second screen, close it, and it is back in the middle of the first one - which
     * reads as the window being stuck to that monitor, because nothing you do to it
     * survives.
     * <p>
     * A saved position is only used while it still lands on a screen that is connected.
     * A monitor unplugged since, or a resolution change, would otherwise leave the window
     * off in space with nothing to click to bring it back. Same guard the Shatters overlay
     * has always had.
     * <p>
     * {@code -Dsonar.monitor=N} (1-based) still wins and is never written down. It is for
     * running a test build without windows opening over whatever is on the main screen.
     */
    private void placeFrame() {
        String monitor = System.getProperty("sonar.monitor");
        if (monitor != null) {
            try {
                GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
                int index = Integer.parseInt(monitor.trim()) - 1;
                if (index >= 0 && index < screens.length) {
                    Rectangle bounds = screens[index].getDefaultConfiguration().getBounds();
                    frame.setLocation(bounds.x + Math.max(0, (bounds.width - frame.getWidth()) / 2),
                            bounds.y + Math.max(0, (bounds.height - frame.getHeight()) / 2));
                    return;
                }
            } catch (RuntimeException ignored) {
                // falls through to the default placement
            }
        }
        try {
            String saved = sonar.util.PropertiesManager.getProperty("sonar.frame.pos");
            if (saved != null) {
                String[] p = saved.split(",");
                Rectangle where = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        frame.getWidth(), frame.getHeight());
                if (onAConnectedScreen(where)) {
                    frame.setLocation(where.x, where.y);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // an unreadable position is no position
        }
        frame.setLocationRelativeTo(null);
    }

    /** Whether any part of this rectangle would land on a monitor that is actually plugged in. */
    private static boolean onAConnectedScreen(Rectangle where) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().intersects(where)) return true;
        }
        return false;
    }

    /**
     * Quest data, received when visiting the quest room.
     *
     * @param q the quests the server sent.
     */
    public static void updateQuests(QuestData[] q) {
        SonarShell.updateQuests(q);
    }

    /**
     * The sniffer starting or stopping, which the left rail shows.
     *
     * @param running whether packets are being read.
     */
    public static void setStateOfSniffer(boolean running) {
        SonarShell.snifferState(running);
    }

    /**
     * Getter for the main object.
     *
     * @return The main sonar frame object.
     */
    public static JFrame getFrame() {
        return frame;
    }
}
