package forge.gui.framework;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;

import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedLabel;

/**
 * The tab label object in drag layout.
 * No modification should be necessary to this object.
 * Simply call the constructor with a title string argument.
 */
@SuppressWarnings("serial")
public final class DragTab extends SkinnedLabel implements ILocalRepaint {
    private boolean selected = false;
    private int priority = 10;
    private float flashIntensity = 0f;

    /**
     * The tab label object in drag layout.
     * No modification should be necessary to this object.
     * Simply call the constructor with a title string argument.
     * 
     * @param title0 &emsp; {java.lang.String}
     */
    public DragTab(final String title0) {
        super(title0);
        setToolTipText(title0);
        setOpaque(false);
        setSelected(false);
        setBorder(new EmptyBorder(2, 5, 2, 5));
        this.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));

        this.addMouseListener(SRearrangingUtil.getRearrangeClickEvent());
        this.addMouseMotionListener(SRearrangingUtil.getRearrangeDragEvent());
        this.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (e.isPopupTrigger()) { showContextMenu(e); }
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isPopupTrigger()) { showContextMenu(e); }
            }
        });
    }

    /** Right-click menu to detach this tab's document into its own window (e.g. on a second monitor), or dock it back. */
    private void showContextMenu(final MouseEvent e) {
        final IVDoc<? extends ICDoc> doc = getDoc();
        if (doc == null) { return; }

        final JPopupMenu menu = new JPopupMenu();
        final JMenuItem item;
        if (SFloatingDocs.isFloating(doc)) {
            item = new JMenuItem(SFloatingDocs.getReattachCaption());
            item.addActionListener(evt -> SFloatingDocs.reattach(doc));
        } else {
            item = new JMenuItem(SFloatingDocs.getDetachCaption());
            item.addActionListener(evt -> SFloatingDocs.detach(doc));
        }
        menu.add(item);
        menu.show(this, e.getX(), e.getY());
    }

    private IVDoc<? extends ICDoc> getDoc() {
        if (getParent() == null || !(getParent().getParent() instanceof DragCell cell)) { return null; }
        for (final IVDoc<? extends ICDoc> doc : cell.getDocs()) {
            if (doc.getTabLabel() == this) { return doc; }
        }
        return null;
    }

    /** @param isSelected0 &emsp; boolean */
    public void setSelected(final boolean isSelected0) {
        selected = isSelected0;
        repaintSelf();
    }

    /** True when this is the active tab in its DragCell. */
    public boolean isSelected() {
        return selected;
    }

    /** Decreases display priority of this tab in relation to its siblings in an overflow case. */
    public void priorityDecrease() {
        priority++;
    }

    /** Sets this tab as first to be displayed if siblings overflow. */
    public void priorityOne() {
        priority = 1;
    }

    /**
     * Returns display priority of this tab in relation to its siblings in an overflow case.
     * @return int
     */
    public int getPriority() {
        return priority;
    }

    // There should be no need for this method.
    @SuppressWarnings("unused")
    private void setPriority() {
        // Intentionally empty.
    }

    /** Sets the red flash overlay intensity (0..1) and repaints. */
    public void setFlashIntensity(final float intensity) {
        flashIntensity = Math.max(0f, Math.min(1f, intensity));
        repaintSelf();
    }

    @Override
    public void repaintSelf() {
        final Dimension d = DragTab.this.getSize();
        repaint(0, 0, d.width, d.height);
    }

    @Override
    public void paintComponent(final Graphics g) {
        if (!selected) {
            FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_INACTIVE));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
            FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_BORDERS));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
        }
        else {
            FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_ACTIVE));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
            FSkin.setGraphicsColor(g, FSkin.getColor(FSkin.Colors.CLR_BORDERS));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
        }

        if (flashIntensity > 0f) {
            g.setColor(new Color(1f, 0f, 0f, flashIntensity));
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() * 2, 6, 6);
        }

        super.paintComponent(g);
    }
}
