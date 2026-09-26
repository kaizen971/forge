package forge.gui.framework;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import forge.Singletons;
import forge.util.Localizer;
import forge.view.FDialog;
import forge.view.FView;

/**
 * Lets documents (tabs) of the drag layout be detached into their own windows,
 * e.g. to move the battlefield, hand or stack of the match screen to a second monitor.
 * <p>
 * A floating window holds one or more panes laid out with split panes (below / to the right),
 * each pane being a {@link DragCell} that can hold several tabs. Floating windows are saved with
 * the screen layout (see {@link SLayoutIO}) and reopened at the same place; closing a window docks
 * its documents back into the main window.
 */
public final class SFloatingDocs {
    /** Where a document goes when moved into an existing floating window. */
    public enum Placement { TAB, BELOW, RIGHT }

    private static final List<FloatingDocWindow> windows = new ArrayList<>();

    private SFloatingDocs() {
    }

    //========== Captions

    private static String message(final String key, final String english) {
        return Localizer.getInstance().getMessageorUseDefault(key, english);
    }

    static String getDetachCaption() {
        return message("lblDetachToWindow", "Detach to new window");
    }

    static String getReattachCaption() {
        return message("lblReattachToMainWindow", "Reattach to main window");
    }

    static String getMoveToCaption(final FloatingDocWindow window) {
        return message("lblMoveToWindow", "Move to window") + " « " + window.getTitle() + " »";
    }

    static String getPlacementCaption(final Placement placement) {
        switch (placement) {
            case BELOW: return message("lblPlaceBelow", "Below");
            case RIGHT: return message("lblPlaceRight", "To the right");
            default:    return message("lblPlaceAsTab", "As a tab");
        }
    }

    //========== Queries

    public static boolean isFloating(final IVDoc<? extends ICDoc> doc) {
        return doc.getParentCell() != null && doc.getParentCell().isFloating();
    }

    static List<FloatingDocWindow> getWindows() {
        return new ArrayList<>(windows);
    }

    static FloatingDocWindow getWindowOf(final IVDoc<? extends ICDoc> doc) {
        for (final FloatingDocWindow window : windows) {
            if (window.getDocs().contains(doc)) {
                return window;
            }
        }
        return null;
    }

    //========== Actions

    /** Moves a document into a new window placed over its current location. */
    public static void detach(final IVDoc<? extends ICDoc> doc) {
        final FloatingDocWindow source = getWindowOf(doc);
        if (source != null && source.getDocs().size() == 1) {
            return; //already alone in its own window
        }
        final DragCell cell = doc.getParentCell();
        if (cell == null) {
            return;
        }
        final Rectangle bounds = cell.isShowing() ? new Rectangle(cell.getLocationOnScreen(), cell.getSize()) : null;

        removeFromCurrentPlace(doc);
        final FloatingDocWindow window = new FloatingDocWindow(getVisibleBounds(bounds));
        windows.add(window);
        window.addPane(doc, JSplitPane.VERTICAL_SPLIT, 0.5);
        window.setVisible(true);
        SLayoutIO.saveLayout(null);
    }

    /** Moves a document (from the main window or another floating window) into an existing floating window. */
    public static void moveToWindow(final IVDoc<? extends ICDoc> doc, final FloatingDocWindow target, final Placement placement) {
        final FloatingDocWindow source = getWindowOf(doc);
        if (source == target && target.getDocs().size() == 1) {
            return;
        }
        if (placement == Placement.TAB && target.getFirstCell() == doc.getParentCell()) {
            return;
        }

        removeFromCurrentPlace(doc);
        switch (placement) {
            case BELOW:
                target.addPane(doc, JSplitPane.VERTICAL_SPLIT, 0.5);
                break;
            case RIGHT:
                target.addPane(doc, JSplitPane.HORIZONTAL_SPLIT, 0.5);
                break;
            default:
                target.addAsTab(doc);
        }
        target.toFront();
        SLayoutIO.saveLayout(null);
    }

    /** Docks a floating document back into the biggest cell of the main window. */
    public static void reattach(final IVDoc<? extends ICDoc> doc) {
        if (!isFloating(doc)) {
            return;
        }
        removeFromCurrentPlace(doc);
        addToMainWindow(doc);
        SLayoutIO.saveLayout(null);
    }

    private static void closeByUser(final FloatingDocWindow window) {
        final List<IVDoc<? extends ICDoc>> docs = window.getDocs();
        windows.remove(window);
        window.dispose();
        for (final IVDoc<? extends ICDoc> doc : docs) {
            doc.getParentCell().removeDoc(doc);
            addToMainWindow(doc);
        }
        SLayoutIO.saveLayout(null);
    }

    /** Removes a document from its cell, cleaning up the main layout or the floating window it leaves. */
    private static void removeFromCurrentPlace(final IVDoc<? extends ICDoc> doc) {
        final DragCell cell = doc.getParentCell();
        if (cell == null) {
            return;
        }
        if (cell.isFloating()) {
            final FloatingDocWindow window = getWindowOf(doc);
            cell.removeDoc(doc);
            if (window != null) {
                window.removeEmptyPanes();
            }
            return;
        }

        cell.removeDoc(doc);
        if (cell.getDocs().isEmpty()) {
            SRearrangingUtil.fillGap(cell);
            FView.SINGLETON_INSTANCE.removeDragCell(cell);
        } else {
            cell.refresh();
        }
        for (final DragCell c : FView.SINGLETON_INSTANCE.getDragCells()) {
            c.updateRoughBounds();
        }
        SRearrangingUtil.updateBorders();
    }

    private static void addToMainWindow(final IVDoc<? extends ICDoc> doc) {
        DragCell biggest = null;
        long biggestArea = -1;
        for (final DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) {
            final long area = (long) cell.getW() * cell.getH();
            if (area > biggestArea) {
                biggest = cell;
                biggestArea = area;
            }
        }
        if (biggest != null) {
            biggest.addDoc(doc);
            biggest.setSelected(doc);
            biggest.refresh();
        }
    }

    //========== Persistence (see SLayoutIO)

    /** Saved state of a pane: its tabs and how it is attached to the panes before it. */
    static final class PaneState {
        int orientation = JSplitPane.VERTICAL_SPLIT;
        double divider = 0.5;
        EDocID selected;
        final List<EDocID> docs = new ArrayList<>();
    }

    /** Saved state of a floating window; bounds are absolute screen pixels so windows can live on another monitor. */
    static final class WindowState {
        /** Normal (non full screen) bounds. */
        final Rectangle bounds = new Rectangle();
        boolean fullScreen;
        final List<PaneState> panes = new ArrayList<>();
    }

    static List<WindowState> getWindowStates() {
        final List<WindowState> states = new ArrayList<>();
        for (final FloatingDocWindow window : windows) {
            states.add(window.getState());
        }
        return states;
    }

    /** Reopens a saved floating window, skipping documents already placed elsewhere. */
    static void open(final WindowState state, final Collection<EDocID> excluded) {
        final FloatingDocWindow window = new FloatingDocWindow(getVisibleBounds(state.bounds));
        for (final PaneState paneState : state.panes) {
            final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
            for (final EDocID id : paneState.docs) {
                try {
                    final IVDoc<? extends ICDoc> doc = id.getDoc();
                    if (doc != null && !excluded.contains(id) && getWindowOf(doc) == null && !docs.contains(doc)) {
                        docs.add(doc);
                    }
                } catch (final IllegalArgumentException e) {
                    System.err.println("Failed to get floating doc for " + id);
                }
            }
            if (docs.isEmpty()) {
                continue;
            }
            final DragCell cell = window.addEmptyPane(paneState.orientation, paneState.divider);
            for (final IVDoc<? extends ICDoc> doc : docs) {
                cell.addDoc(doc);
            }
            if (paneState.selected != null && paneState.docs.contains(paneState.selected)) {
                cell.setSelected(paneState.selected.getDoc());
            }
        }
        if (window.panes.isEmpty()) {
            window.dispose();
            return;
        }
        windows.add(window);
        window.rebuild();
        if (state.fullScreen) {
            window.setFullScreen(true);
        }
        window.setVisible(true);
    }

    /** Closes every floating window without docking its documents back (the layout is about to be rebuilt). */
    static void closeAll() {
        final List<FloatingDocWindow> toClose = new ArrayList<>(windows);
        windows.clear();
        for (final FloatingDocWindow window : toClose) {
            window.dispose();
        }
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

    //========== Window

    private static final class Pane {
        private final DragCell cell = new DragCell();
        /** How this pane is attached to the panes before it (ignored for the first pane). */
        private final int orientation;
        /** Share of the split given to the panes before this one. */
        private double divider;

        private Pane(final int orientation, final double divider) {
            this.orientation = orientation;
            this.divider = Math.max(0.05, Math.min(0.95, divider));
            cell.setFloating(true);
        }
    }

    @SuppressWarnings("serial")
    static final class FloatingDocWindow extends FDialog {
        private final List<Pane> panes = new ArrayList<>();
        private final List<JSplitPane> splits = new ArrayList<>();
        private final JPanel content = new JPanel(new BorderLayout());
        private final boolean hasSavedBounds;
        private boolean applyingDividers;
        /** Window bounds to restore when leaving full screen; null when not in full screen. */
        private Rectangle boundsBeforeFullScreen;

        private FloatingDocWindow(final Rectangle bounds) {
            super(Singletons.getView().getFrame(), false, true, "0");
            hasSavedBounds = bounds != null;
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            content.setOpaque(false);
            content.setMinimumSize(new Dimension(0, 0)); //always fit the window instead of overflowing it
            add(content, "grow, push");

            if (hasSavedBounds) {
                setBounds(bounds);
            } else {
                setSize(Math.max(400, getOwner().getWidth() / 3), Math.max(300, getOwner().getHeight() / 2));
            }

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    closeByUser(FloatingDocWindow.this);
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
        public void setLocationRelativeTo(final Component c) {
            if (hasSavedBounds) { return; } //keep the saved / detached position, e.g. on a second monitor
            super.setLocationRelativeTo(c);
        }

        //========== Full screen: covers the whole monitor the window is on

        @Override
        public boolean supportsFullScreen() {
            return true;
        }

        @Override
        public boolean isFullScreen() {
            return boundsBeforeFullScreen != null;
        }

        @Override
        public void setFullScreen(final boolean fullScreen) {
            if (fullScreen == isFullScreen()) { return; }
            if (fullScreen) {
                boundsBeforeFullScreen = getBounds();
                setBounds(getScreenBounds(boundsBeforeFullScreen));
            } else {
                final Rectangle restore = boundsBeforeFullScreen;
                boundsBeforeFullScreen = null;
                setBounds(restore);
            }
            getTitleBar().refreshFullScreenButton();
            SwingUtilities.invokeLater(this::applyDividers);
            SLayoutIO.saveLayout(null);
        }

        /** @return bounds of the monitor containing the center of the given area (default monitor if none) */
        private static Rectangle getScreenBounds(final Rectangle area) {
            final Point center = new Point((int) area.getCenterX(), (int) area.getCenterY());
            for (final GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                final Rectangle screen = device.getDefaultConfiguration().getBounds();
                if (screen.contains(center)) {
                    return screen;
                }
            }
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        }

        List<IVDoc<? extends ICDoc>> getDocs() {
            final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
            for (final Pane pane : panes) {
                docs.addAll(pane.cell.getDocs());
            }
            return docs;
        }

        DragCell getFirstCell() {
            return panes.isEmpty() ? null : panes.get(0).cell;
        }

        private DragCell addEmptyPane(final int orientation, final double divider) {
            final Pane pane = new Pane(orientation, divider);
            panes.add(pane);
            return pane.cell;
        }

        private void addPane(final IVDoc<? extends ICDoc> doc, final int orientation, final double divider) {
            final DragCell cell = addEmptyPane(orientation, divider);
            cell.addDoc(doc);
            cell.setSelected(doc);
            rebuild();
        }

        private void addAsTab(final IVDoc<? extends ICDoc> doc) {
            if (panes.isEmpty()) {
                addPane(doc, JSplitPane.VERTICAL_SPLIT, 0.5);
                return;
            }
            final DragCell cell = panes.get(0).cell;
            cell.addDoc(doc);
            cell.setSelected(doc);
            cell.refresh();
            rebuild();
        }

        /** Drops panes left empty; closes the window once it holds no document. */
        private void removeEmptyPanes() {
            panes.removeIf(pane -> pane.cell.getDocs().isEmpty());
            if (panes.isEmpty()) {
                windows.remove(this);
                dispose();
            } else {
                rebuild();
            }
        }

        /** Rebuilds the chain of split panes: each pane is attached below or to the right of the previous ones. */
        private void rebuild() {
            splits.clear();
            content.removeAll();
            Component root = panes.get(0).cell;
            for (int i = 1; i < panes.size(); i++) {
                final Pane pane = panes.get(i);
                final JSplitPane split = new JSplitPane(pane.orientation, true, root, pane.cell);
                split.setBorder(null);
                split.setOpaque(false);
                split.setDividerSize(8);
                split.setContinuousLayout(true);
                split.setResizeWeight(pane.divider);
                split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                    if (applyingDividers) { return; }
                    final double proportion = getProportion(split);
                    if (proportion > 0) {
                        pane.divider = proportion;
                        SLayoutIO.saveLayout(null);
                    }
                });
                splits.add(split);
                root = split;
            }
            content.add(root, BorderLayout.CENTER);
            updateTitle();
            content.revalidate();
            content.repaint();
            SwingUtilities.invokeLater(this::applyDividers);
        }

        /** Divider locations can only be set once split panes have a size: apply them outermost first. */
        private void applyDividers() {
            applyingDividers = true;
            try {
                validate();
                for (int i = splits.size() - 1; i >= 0; i--) {
                    final JSplitPane split = splits.get(i);
                    if (split.getWidth() > 0 && split.getHeight() > 0) {
                        split.setDividerLocation(panes.get(i + 1).divider);
                        split.validate();
                    }
                }
            } finally {
                applyingDividers = false;
            }
        }

        private static double getProportion(final JSplitPane split) {
            final int size = (split.getOrientation() == JSplitPane.VERTICAL_SPLIT ? split.getHeight() : split.getWidth())
                    - split.getDividerSize();
            return size > 0 ? (double) split.getDividerLocation() / size : -1;
        }

        private void updateTitle() {
            final List<String> names = new ArrayList<>();
            for (final IVDoc<? extends ICDoc> doc : getDocs()) {
                names.add(doc.getTabLabel().getText());
            }
            setTitle(String.join(" + ", names));
        }

        private WindowState getState() {
            final WindowState state = new WindowState();
            state.bounds.setBounds(isFullScreen() ? boundsBeforeFullScreen : getBounds());
            state.fullScreen = isFullScreen();
            for (final Pane pane : panes) {
                final PaneState paneState = new PaneState();
                paneState.orientation = pane.orientation;
                paneState.divider = pane.divider;
                if (pane.cell.getSelected() != null) {
                    paneState.selected = pane.cell.getSelected().getDocumentID();
                }
                for (final IVDoc<? extends ICDoc> doc : pane.cell.getDocs()) {
                    paneState.docs.add(doc.getDocumentID());
                }
                state.panes.add(paneState);
            }
            return state;
        }
    }
}
