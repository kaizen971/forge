/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.toolbox;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * Paints the flat, rounded button background shared by {@link FButton},
 * {@link FLabel} buttons and the start button when the modern (FlatLaf) look is active.
 * Colors come from the current skin so custom skins keep working.
 */
public final class ModernButtonPainter {
    public enum State { NORMAL, HOVER, PRESSED, SELECTED, DISABLED }

    private static final int ARC = 10;
    private static final Color BORDER = new Color(255, 255, 255, 30);
    private static final AlphaComposite DISABLED_COMPOSITE = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f);

    private ModernButtonPainter() {
    }

    /**
     * @param primary true for call-to-action buttons (e.g. Start), filled with the skin accent color
     * @param focused true to draw an accent focus ring
     */
    public static void paint(final Graphics2D g0, final int w, final int h, final State state,
            final boolean primary, final boolean focused) {
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            if (state == State.DISABLED) {
                g.setComposite(DISABLED_COMPOSITE);
            }
            final Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, ARC, ARC);
            g.setColor(getFill(state, primary));
            g.fill(shape);

            if (focused && state != State.DISABLED) {
                g.setColor(FSkin.stepColor(getAccent(), 40));
                g.setStroke(new BasicStroke(1.5f));
            } else {
                g.setColor(BORDER);
            }
            g.draw(shape);
        } finally {
            g.dispose();
        }
    }

    private static Color getAccent() {
        return FSkin.getColor(FSkin.Colors.CLR_ACTIVE).getColor();
    }

    private static Color getFill(final State state, final boolean primary) {
        final Color accent = getAccent();
        final Color base = primary ? accent : FSkin.getColor(FSkin.Colors.CLR_INACTIVE).getColor();
        switch (state) {
            case HOVER:
                return primary ? FSkin.stepColor(accent, 20) : FSkin.getColor(FSkin.Colors.CLR_HOVER).getColor();
            case PRESSED:
                return FSkin.stepColor(base, -20);
            case SELECTED:
                return accent;
            default:
                return base;
        }
    }
}
