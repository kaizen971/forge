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
 * A floating window holds a tree of panes: each leaf is a {@link DragCell} (one or more tabs) and each
 * node splits its area between two children, below or to the right of each other, with a resizable divider.
 * Tabs can be moved with the tab right-click menu or dragged between cells of any window (see
 * {@link SRearrangingUtil}). Floating windows are saved with the screen layout (see {@link SLayoutIO})
 * and reopened at the same place; closing a window docks its documents back into the main window.
 */
public final class SFloatingDocs {
    /** Where documents go in a floating window, relative to a pane (or to the whole window from the menu). */
    public enum Placement { TAB, ABOVE, BELOW, LEFT, RIGHT }

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
            case ABOVE: return message("lblPlaceAbove", "Above");
            case BELOW: return message("lblPlaceBelow", "Below");
            case LEFT:  return message("lblPlaceLeft", "To the left");
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
        return doc.getParentCell() == null ? null : getWindowOf(doc.getParentCell());
    }

    static FloatingDocWindow getWindowOf(final DragCell cell) {
        for (final FloatingDocWindow window : windows) {
            if (window.findLeaf(cell) != null) {
                return window;
            }
        }
        return null;
    }

    /** @return true if the screen point is over a visible floating window */
    static boolean isOverFloatingWindow(final Point screenPoint) {
        for (final FloatingDocWindow window : windows) {
            if (window.isShowing() && window.getBounds().contains(screenPoint)) {
                return true;
            }
        }
        return false;
    }

    /** @return the floating cell under the screen point, or null */
    static DragCell getFloatingCellAt(final Point screenPoint) {
        for (final FloatingDocWindow window : windows) {
            if (!window.isShowing() || !window.getBounds().contains(screenPoint)) {
                continue;
            }
            for (final Leaf leaf : window.getLeaves()) {
                if (leaf.cell.isShowing()
                        && new Rectangle(leaf.cell.getLocationOnScreen(), leaf.cell.getSize()).contains(screenPoint)) {
                    return leaf.cell;
                }
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
        final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
        docs.add(doc);
        detach(docs, cell.isShowing() ? new Rectangle(cell.getLocationOnScreen(), cell.getSize()) : null);
    }

    /** Moves documents into a new window with the given screen bounds (a default size when null). */
    static void detach(final List<IVDoc<? extends ICDoc>> docs, final Rectangle bounds) {
        if (docs.isEmpty()) {
            return;
        }
        for (final IVDoc<? extends ICDoc> doc : docs) {
            removeFromCurrentPlace(doc);
        }
        final FloatingDocWindow window = new FloatingDocWindow(getVisibleBounds(bounds));
        windows.add(window);
        final Leaf leaf = new Leaf();
        window.root = leaf;
        addDocs(leaf.cell, docs);
        window.rebuild();
        window.setVisible(true);
        SLayoutIO.saveLayout(null);
    }

    /** Moves a document into an existing floating window, relative to the whole window (tab right-click menu). */
    public static void moveToWindow(final IVDoc<? extends ICDoc> doc, final FloatingDocWindow target, final Placement placement) {
        final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
        docs.add(doc);
        moveDocs(docs, target, null, placement);
        target.toFront();
    }

    /** Moves documents next to (or into, for {@link Placement#TAB}) a floating cell, e.g. at the end of a tab drag. */
    static void moveDocsTo(final List<IVDoc<? extends ICDoc>> docs, final DragCell targetCell, final Placement placement) {
        final FloatingDocWindow target = getWindowOf(targetCell);
        if (target != null) {
            moveDocs(docs, target, target.findLeaf(targetCell), placement);
        }
    }

    /**
     * @param targetLeaf pane to place documents into or next to; null for the whole window
     *                   (tabs then go into its first pane)
     */
    private static void moveDocs(final List<IVDoc<? extends ICDoc>> docs, final FloatingDocWindow target,
            final Leaf targetLeaf, final Placement placement) {
        final List<IVDoc<? extends ICDoc>> toMove = new ArrayList<>(docs);
        final DragCell targetCell = targetLeaf != null ? targetLeaf.cell
                : (target.getLeaves().isEmpty() ? null : target.getLeaves().get(0).cell);
        if (placement == Placement.TAB) {
            toMove.removeIf(doc -> doc.getParentCell() == targetCell);
        }
        if (toMove.isEmpty()) {
            return;
        }
        //moving every document of a pane next to itself, or of a window into itself, would change nothing
        if (targetLeaf != null && toMove.containsAll(targetLeaf.cell.getDocs())) {
            return;
        }
        if (targetLeaf == null && toMove.containsAll(target.getDocs())) {
            return;
        }

        for (final IVDoc<? extends ICDoc> doc : toMove) {
            removeFromCurrentPlace(doc);
        }
        if (placement == Placement.TAB && targetCell != null && target.findLeaf(targetCell) != null) {
            addDocs(targetCell, toMove);
        } else {
            final Leaf leaf = new Leaf();
            target.insert(leaf, targetLeaf, placement == Placement.TAB ? Placement.BELOW : placement);
            addDocs(leaf.cell, toMove);
        }
        target.rebuild();
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

    private static void addDocs(final DragCell cell, final List<IVDoc<? extends ICDoc>> docs) {
        for (final IVDoc<? extends ICDoc> doc : docs) {
            cell.addDoc(doc);
        }
        cell.setSelected(docs.get(docs.size() - 1));
        cell.refresh();
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

    /**
     * Removes a document from its cell, cleaning up what it leaves: in the main window the gap is filled,
     * in a floating window an empty pane is removed (and the window closed once empty).
     */
    static void removeFromCurrentPlace(final IVDoc<? extends ICDoc> doc) {
        final DragCell cell = doc.getParentCell();
        if (cell == null) {
            return;
        }
        if (cell.isFloating()) {
            final FloatingDocWindow window = getWindowOf(cell);
            cell.removeDoc(doc);
            if (window != null && cell.getDocs().isEmpty()) {
                window.removeLeaf(window.findLeaf(cell));
            } else {
                cell.refresh();
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

    /** Saved pane tree node: either a split between two children, or a leaf pane holding tabs. */
    static final class NodeState {
        boolean split;
        int orientation = JSplitPane.VERTICAL_SPLIT;
        double divider = 0.5;
        NodeState first;
        NodeState second;
        EDocID selected;
        final List<EDocID> docs = new ArrayList<>();
    }

    /** Saved state of a floating window; bounds are absolute screen pixels so windows can live on another monitor. */
    static final class WindowState {
        /** Normal (non full screen) bounds. */
        final Rectangle bounds = new Rectangle();
        boolean fullScreen;
        NodeState root;
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
        window.root = buildNode(state.root, excluded);
        if (window.root == null) {
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

    /** @return the pane tree for a saved node, without the panes whose documents are all unavailable */
    private static Node buildNode(final NodeState state, final Collection<EDocID> excluded) {
        if (state == null) {
            return null;
        }
        if (state.split) {
            final Node first = buildNode(state.first, excluded);
            final Node second = buildNode(state.second, excluded);
            if (first == null || second == null) {
                return first != null ? first : second;
            }
            final Split split = new Split(state.orientation, state.divider);
            split.first = first;
            split.second = second;
            return split;
        }

        final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
        for (final EDocID id : state.docs) {
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
            return null;
        }
        final Leaf leaf = new Leaf();
        for (final IVDoc<? extends ICDoc> doc : docs) {
            leaf.cell.addDoc(doc);
        }
        if (state.selected != null && state.docs.contains(state.selected)) {
            leaf.cell.setSelected(state.selected.getDoc());
        }
        return leaf;
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

    //========== Pane tree

    private abstract static class Node {
    }

    private static final class Leaf extends Node {
        private final DragCell cell = new DragCell();

        private Leaf() {
            cell.setFloating(true);
        }
    }

    private static final class Split extends Node {
        private final int orientation;
        /** Share of the area given to the first child. */
        private double divider;
        private Node first;
        private Node second;

        private Split(final int orientation, final double divider) {
            this.orientation = orientation;
            this.divider = Math.max(0.05, Math.min(0.95, divider));
        }
    }

    //========== Window

    @SuppressWarnings("serial")
    static final class FloatingDocWindow extends FDialog {
        private Node root;
        private final List<JSplitPane> splits = new ArrayList<>();
        private final List<Split> splitNodes = new ArrayList<>();
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

        //========== Tree operations

        List<IVDoc<? extends ICDoc>> getDocs() {
            final List<IVDoc<? extends ICDoc>> docs = new ArrayList<>();
            for (final Leaf leaf : getLeaves()) {
                docs.addAll(leaf.cell.getDocs());
            }
            return docs;
        }

        DragCell getFirstCell() {
            final List<Leaf> leaves = getLeaves();
            return leaves.isEmpty() ? null : leaves.get(0).cell;
        }

        private List<Leaf> getLeaves() {
            final List<Leaf> leaves = new ArrayList<>();
            collectLeaves(root, leaves);
            return leaves;
        }

        private static void collectLeaves(final Node node, final List<Leaf> leaves) {
            if (node instanceof Leaf leaf) {
                leaves.add(leaf);
            } else if (node instanceof Split split) {
                collectLeaves(split.first, leaves);
                collectLeaves(split.second, leaves);
            }
        }

        private Leaf findLeaf(final DragCell cell) {
            for (final Leaf leaf : getLeaves()) {
                if (leaf.cell == cell) {
                    return leaf;
                }
            }
            return null;
        }

        private Split findParent(final Node node) {
            return findParent(root, node);
        }

        private static Split findParent(final Node current, final Node node) {
            if (!(current instanceof Split split)) {
                return null;
            }
            if (split.first == node || split.second == node) {
                return split;
            }
            final Split inFirst = findParent(split.first, node);
            return inFirst != null ? inFirst : findParent(split.second, node);
        }

        private void replace(final Node oldNode, final Node newNode) {
            final Split parent = findParent(oldNode);
            if (parent == null) {
                root = newNode;
            } else if (parent.first == oldNode) {
                parent.first = newNode;
            } else {
                parent.second = newNode;
            }
        }

        /** Inserts a new pane next to a pane (or the whole window when target is null). */
        private void insert(final Leaf leaf, final Node target, final Placement placement) {
            final Node anchor = target != null ? target : root;
            if (anchor == null) {
                root = leaf;
                return;
            }
            final boolean vertical = placement == Placement.ABOVE || placement == Placement.BELOW;
            final boolean newFirst = placement == Placement.ABOVE || placement == Placement.LEFT;
            final Split split = new Split(vertical ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT, 0.5);
            replace(anchor, split);
            split.first = newFirst ? leaf : anchor;
            split.second = newFirst ? anchor : leaf;
        }

        /** Removes an (empty) pane, its sibling taking its place; closes the window once it holds no pane. */
        private void removeLeaf(final Leaf leaf) {
            if (leaf == null) {
                return;
            }
            final Split parent = findParent(leaf);
            if (parent == null) {
                root = null;
            } else {
                replace(parent, parent.first == leaf ? parent.second : parent.first);
            }
            if (root == null) {
                windows.remove(this);
                dispose();
            } else {
                rebuild();
            }
        }

        //========== Components

        /** Rebuilds the split panes from the pane tree. */
        private void rebuild() {
            splits.clear();
            splitNodes.clear();
            content.removeAll();
            if (root != null) {
                content.add(buildComponent(root), BorderLayout.CENTER);
            }
            updateTitle();
            content.revalidate();
            content.repaint();
            SwingUtilities.invokeLater(this::applyDividers);
        }

        private Component buildComponent(final Node node) {
            if (node instanceof Leaf leaf) {
                return leaf.cell;
            }
            final Split splitNode = (Split) node;
            final JSplitPane split = new JSplitPane(splitNode.orientation, true);
            splits.add(split); //pre-order: outer split panes come first
            splitNodes.add(splitNode);
            split.setTopComponent(buildComponent(splitNode.first));
            split.setBottomComponent(buildComponent(splitNode.second));
            split.setBorder(null);
            split.setOpaque(false);
            split.setDividerSize(8);
            split.setContinuousLayout(true);
            split.setResizeWeight(splitNode.divider);
            split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (applyingDividers) { return; }
                final double proportion = getProportion(split);
                if (proportion > 0) {
                    splitNode.divider = proportion;
                    SLayoutIO.saveLayout(null);
                }
            });
            return split;
        }

        /** Divider locations can only be set once split panes have a size: apply them outermost first. */
        private void applyDividers() {
            applyingDividers = true;
            try {
                validate();
                for (int i = 0; i < splits.size(); i++) {
                    final JSplitPane split = splits.get(i);
                    if (split.getWidth() > 0 && split.getHeight() > 0) {
                        split.setDividerLocation(splitNodes.get(i).divider);
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

        //========== State

        private WindowState getState() {
            final WindowState state = new WindowState();
            state.bounds.setBounds(isFullScreen() ? boundsBeforeFullScreen : getBounds());
            state.fullScreen = isFullScreen();
            state.root = getNodeState(root);
            return state;
        }

        private static NodeState getNodeState(final Node node) {
            if (node == null) {
                return null;
            }
            final NodeState state = new NodeState();
            if (node instanceof Split split) {
                state.split = true;
                state.orientation = split.orientation;
                state.divider = split.divider;
                state.first = getNodeState(split.first);
                state.second = getNodeState(split.second);
            } else {
                final DragCell cell = ((Leaf) node).cell;
                if (cell.getSelected() != null) {
                    state.selected = cell.getSelected().getDocumentID();
                }
                for (final IVDoc<? extends ICDoc> doc : cell.getDocs()) {
                    state.docs.add(doc.getDocumentID());
                }
            }
            return state;
        }
    }
}
