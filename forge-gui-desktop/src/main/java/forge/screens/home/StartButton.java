package forge.screens.home;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;

import javax.swing.ButtonModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedButton;
import forge.toolbox.ModernButtonPainter;
import forge.util.Localizer;

@SuppressWarnings("serial")
public class StartButton extends SkinnedButton {
    /** Size of the accent pill painted with the modern (FlatLaf) look, centered in the button bounds. */
    private static final Dimension MODERN_SIZE = new Dimension(200, 48);

    public StartButton() {
        setOpaque(false);
        setContentAreaFilled(false);
        setBorder((Border)null);
        setBorderPainted(false);
        setRolloverEnabled(true);
        setRolloverIcon(FSkin.getIcon(FSkinProp.IMG_BTN_START_OVER));
        setIcon(FSkin.getIcon(FSkinProp.IMG_BTN_START_UP));
        setPressedIcon(FSkin.getIcon(FSkinProp.IMG_BTN_START_DOWN));
        // Accessible name.
        this.getAccessibleContext().setAccessibleName("Start game");
        addFocusListener(new FocusListener() {
            @Override
            public void focusLost(FocusEvent arg0) {
                setIcon(FSkin.getIcon(FSkinProp.IMG_BTN_START_UP));
            }

            @Override
            public void focusGained(FocusEvent arg0) {
                setIcon(FSkin.getIcon(FSkinProp.IMG_BTN_START_OVER));
            }
        });

        addActionListener(e -> {
            setEnabled(false);

            // ensure the click action can resolve before we allow the button to be clicked again
            SwingUtilities.invokeLater(() -> setEnabled(true));
        });
    }

    @Override
    public Dimension getPreferredSize() {
        return FSkin.isFlatLaf() ? new Dimension(MODERN_SIZE) : super.getPreferredSize();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        if (!FSkin.isFlatLaf()) {
            super.paintComponent(g);
            return;
        }

        final ButtonModel model = getModel();
        final ModernButtonPainter.State state;
        if (!isEnabled()) {
            state = ModernButtonPainter.State.DISABLED;
        } else if (model.isPressed() && model.isArmed()) {
            state = ModernButtonPainter.State.PRESSED;
        } else if (model.isRollover()) {
            state = ModernButtonPainter.State.HOVER;
        } else {
            state = ModernButtonPainter.State.NORMAL;
        }

        final int w = Math.min(MODERN_SIZE.width, getWidth());
        final int h = Math.min(MODERN_SIZE.height, getHeight());
        final int x = (getWidth() - w) / 2;
        final int y = (getHeight() - h) / 2;
        final Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.translate(x, y);
            ModernButtonPainter.paint(g2d, w, h, state, true, hasFocus());

            final String text = Localizer.getInstance().getMessage("lblStart");
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setFont(FSkin.getBoldFont(20).getBaseFont());
            g2d.setColor(FSkin.getColor(FSkin.Colors.CLR_TEXT).getColor());
            final FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
        } finally {
            g2d.dispose();
        }
    }
}
