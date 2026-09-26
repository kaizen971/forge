package forge.gui.framework;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.WindowConstants;

import forge.Singletons;
import forge.util.Localizer;
import forge.view.FDialog;
import forge.view.FView;

/**
 * Lets a document (tab) of the drag layout be detached into its own window,
 * e.g. to move the battlefield, hand or stack of the match screen to a second monitor.
 * <p>
 * Floating windows are saved with the screen layout (see {@link SLayoutIO}) and
 * reopened at the same place; closing a window docks its document back into the main window.
 */
public final class SFloatingDocs {
    private static final Map<EDocID, FloatingDocWindow> windows = new LinkedHashMap<>();

    private SFloatingDocs() {
    }

    public static String getDetachCaption() {
        return Localizer.getInstance().getMessageorUseDefault("lblDetachToWindow", "Detach to window");
    }

    public static String getReattachCaption() {
        return Localizer.getInstance().getMessageorUseDefault("lblReattachToMainWindow", "Reattach to main window");
    }

    public static boolean isFloating(final IVDoc<? extends ICDoc> doc) {
        return doc.getParentCell() != null && doc.getParentCell().isFloating();
    }

    /** Moves a document from the main drag layout into a new window placed over its current location. */
    public static void detach(final IVDoc<? extends ICDoc> doc) {
        final DragCell source = doc.getParentCell();
        if (source == null || source.isFloating() || windows.containsKey(doc.getDocumentID())) {
            return;
        }

        Rectangle bounds = null;
        if (source.isShowing()) {
            bounds = new Rectangle(source.getLocationOnScreen(), source.getSize());
        }

        source.removeDoc(doc);
        if (source.getDocs().isEmpty()) {
            SRearrangingUtil.fillGap(source);
            FView.SINGLETON_INSTANCE.removeDragCell(source);
        } else {
            source.refresh();
        }
        for (final DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) {
            cell.updateRoughBounds();
        }
        SRearrangingUtil.updateBorders();

        open(doc, bounds);
        SLayoutIO.saveLayout(null);
    }

    /** Closes the floating window of a document and docks the document back into the biggest cell of the main window. */
    public static void reattach(final IVDoc<? extends ICDoc> doc) {
        final FloatingDocWindow window = windows.remove(doc.getDocumentID());
        if (window == null) {
            return;
        }
        window.cell.removeDoc(doc);
        window.dispose();

        final DragCell target = getBiggestCell();
        if (target != null) {
            target.addDoc(doc);
            target.setSelected(doc);
            target.refresh();
        }
        SLayoutIO.saveLayout(null);
    }

    /** Opens a floating window for a document that is not part of the main drag layout. */
    static void open(final IVDoc<? extends ICDoc> doc, final Rectangle bounds) {
        if (windows.containsKey(doc.getDocumentID())) {
            return;
        }
        final FloatingDocWindow window = new FloatingDocWindow(doc, getVisibleBounds(bounds));
        windows.put(doc.getDocumentID(), window);
        window.setVisible(true);
    }

    /** Closes every floating window without docking its document back (the layout is about to be rebuilt). */
    static void closeAll() {
        final List<FloatingDocWindow> toClose = new ArrayList<>(windows.values());
        windows.clear();
        for (final FloatingDocWindow window : toClose) {
            window.dispose();
        }
    }

    /** Snapshot of the open floating windows, used to save them with the layout. */
    static Map<EDocID, Rectangle> getFloatingBounds() {
        final Map<EDocID, Rectangle> result = new LinkedHashMap<>();
        for (final Map.Entry<EDocID, FloatingDocWindow> e : windows.entrySet()) {
            result.put(e.getKey(), e.getValue().getBounds());
        }
        return result;
    }

    private static DragCell getBiggestCell() {
        DragCell biggest = null;
        long biggestArea = -1;
        for (final DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) {
            final long area = (long) cell.getW() * cell.getH();
            if (area > biggestArea) {
                biggest = cell;
                biggestArea = area;
            }
        }
        return biggest;
    }

    /** @return the given bounds if their center is on a connected screen (a monitor may have been unplugged), otherwise null */
    private static Rectangle getVisibleBounds(final Rectangle bounds) {
        if (bounds == null || bounds.width < 50 || bounds.height < 50) {
            return null;
        }
        final Point center = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
        for (final GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(center)) {
                return bounds;
            }
        }
        return null;
    }

    @SuppressWarnings("serial")
    private static final class FloatingDocWindow extends FDialog {
        private final DragCell cell = new DragCell();
        private final boolean hasSavedBounds;

        private FloatingDocWindow(final IVDoc<? extends ICDoc> doc, final Rectangle bounds) {
            super(Singletons.getView().getFrame(), false, true, "0");
            hasSavedBounds = bounds != null;
            setTitle(doc.getTabLabel().getText());
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

            cell.setFloating(true);
            add(cell, "grow, push");
            cell.addDoc(doc);
            cell.setSelected(doc);

            if (hasSavedBounds) {
                setBounds(bounds);
            } else {
                setSize(Math.max(400, getOwner().getWidth() / 3), Math.max(300, getOwner().getHeight() / 2));
            }

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    reattach(doc);
                }
            });
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(final ComponentEvent e) {
                    SLayoutIO.saveLayout(null);
                }

                @Override
                public void componentResized(final ComponentEvent e) {
                    SLayoutIO.saveLayout(null);
                }
            });
        }

        @Override
        public void setLocationRelativeTo(final java.awt.Component c) {
            if (hasSavedBounds) { return; } //keep the saved / detached position, e.g. on a second monitor
            super.setLocationRelativeTo(c);
        }
    }
}
