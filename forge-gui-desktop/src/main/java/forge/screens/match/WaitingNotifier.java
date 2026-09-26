package forge.screens.match;

import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.SystemTray;
import java.awt.Taskbar;
import java.awt.TrayIcon;
import java.awt.Window;

import forge.Singletons;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;

/**
 * Gets the player's attention when the game waits for their input while no Forge
 * window is active (e.g. the player is looking at another monitor or application):
 * flashes the taskbar button and shows a system notification with the prompt.
 */
public final class WaitingNotifier {
    /** Minimum delay between two system notifications, so priority passes don't spam the player. */
    private static final long NOTIFICATION_INTERVAL_MS = 15_000;

    private static TrayIcon trayIcon;
    private static boolean trayUnavailable;
    private static long lastNotificationTime;

    private WaitingNotifier() {
    }

    /** Must be called on the EDT when the game starts waiting for the local player. */
    public static void notifyWaiting(final String prompt) {
        if (!FModel.getPreferences().getPrefBoolean(FPref.UI_NOTIFY_WHEN_WAITING)) {
            return;
        }
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null) {
            return; //a Forge window (main or detached) is active: the player is already looking at the game
        }

        final Window frame = Singletons.getView().getFrame();
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
                Taskbar.getTaskbar().requestWindowUserAttention(frame);
            }
        } catch (final RuntimeException e) {
            e.printStackTrace();
        }

        final long now = System.currentTimeMillis();
        if (now - lastNotificationTime < NOTIFICATION_INTERVAL_MS) {
            return;
        }
        lastNotificationTime = now;
        final TrayIcon icon = getTrayIcon();
        if (icon != null) {
            final Localizer localizer = Localizer.getInstance();
            final String message = prompt == null || prompt.isBlank()
                    ? localizer.getMessageorUseDefault("lblForgeIsWaitingForYou", "Forge is waiting for you")
                    : prompt;
            icon.displayMessage(localizer.getMessageorUseDefault("lblYourMove", "Your move"), message, TrayIcon.MessageType.INFO);
        }
    }

    private static TrayIcon getTrayIcon() {
        if (trayIcon != null || trayUnavailable) {
            return trayIcon;
        }
        try {
            if (!SystemTray.isSupported()) {
                trayUnavailable = true;
                return null;
            }
            final Image image = Singletons.getView().getFrame().getIconImage();
            if (image == null) {
                return null; //retry on the next notification, once the frame icon is set
            }
            trayIcon = new TrayIcon(image, "Forge");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> Singletons.getView().getFrame().toFront()); //clicking the notification brings Forge back
            SystemTray.getSystemTray().add(trayIcon);
        } catch (final Exception e) {
            e.printStackTrace();
            trayIcon = null;
            trayUnavailable = true;
        }
        return trayIcon;
    }
}
