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
package forge.screens.match;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import forge.Singletons;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.controllers.CDock.ArcState;
import forge.screens.match.views.VField;
import forge.screens.match.views.VStack.StackInstanceTextArea;
import forge.toolbox.FSkin;
import forge.toolbox.FSkin.SkinnedPanel;
import forge.view.FView;
import forge.view.arcane.CardPanel;
import forge.view.arcane.CardPanelContainer;
import forge.view.arcane.util.CardPanelMouseListener;

/**
 * Semi-transparent overlay panel. Should be used with layered panes.
 * 
 */
@SuppressWarnings("serial")
public class TargetingOverlay {
    private final CMatchUI matchUI;
    private final OverlayPanel pnl = new OverlayPanel();
    private final List<CardPanel> cardPanels = new ArrayList<>();
    private final List<Arc> arcsFoeAtk = new ArrayList<>();
    private final List<Arc> arcsFoeDef = new ArrayList<>();
    private final List<Arc> arcsFriend = new ArrayList<>();
    private final ArcAssembler assembler = new ArcAssembler();
    private final Set<Integer> stackItemIDs = new HashSet<>();

    private static class Arc {
        private final int x1, y1, x2, y2;
        /** Optional badge drawn along the arrow, e.g. the attacker's power. Not part of equality. */
        private final String label;

        private Arc(final Point end, final Point start, final String label) {
            x1 = start.x;
            y1 = start.y;
            x2 = end.x;
            y2 = end.y;
            this.label = label;
        }
        
        @Override
        public boolean equals(Object obj)
        {
        	Arc arc = (Arc)obj;
        	return ((arc.x1 == x1) && (arc.x2 == x2) && (arc.y1 == y1) && (arc.y2 == y2));
        }
    }

    private final Set<CardView> cardsVisualized = new HashSet<>();
    private CardPanel activePanel = null;

    //private long lastUpdated = System.currentTimeMillis(); // TODO: determine if timer is needed (see below)
    private int allowedUpdates = 0;
    private final int MAX_CONSECUTIVE_UPDATES = 1;

    private enum ArcConnection {
        Friends,
        FoesAttacking,
        FoesBlocking,
        FriendsStackTargeting,
        FoesStackTargeting
    }

    /**
     * Semi-transparent overlay panel. Should be used with layered panes.
     */
    public TargetingOverlay(final CMatchUI matchUI) {
        this.matchUI = matchUI;
        pnl.setOpaque(false);
        pnl.setVisible(true);
        pnl.setFocusTraversalKeysEnabled(false);
        pnl.setBackground(FSkin.getColor(FSkin.Colors.CLR_ZEBRA));
    }

    /** @return {@link javax.swing.JPanel} */
    public JPanel getPanel() {
        return this.pnl;
    }

    // The original version of assembleArcs, without code to throttle it.
    // Re-added as the new version was causing issues for at least one user.
    private void assembleArcs(final CombatView combat) {
        //List<VField> fields = VMatchUI.SINGLETON_INSTANCE.getFieldViews();
        arcsFoeAtk.clear();
        arcsFoeDef.clear();
        arcsFriend.clear();
        cardPanels.clear();
        cardsVisualized.clear();

        final StackInstanceTextArea activeStackItem = matchUI.getCStack().getView().getHoveredItem();

        switch (matchUI.getCDock().getArcState()) {
            case OFF:
                return;
            case MOUSEOVER:
                // Draw only hovered card
                activePanel = null;
                for (final VField f : matchUI.getFieldViews()) {
                    cardPanels.addAll(f.getTabletop().getCardPanels());
                    final List<CardPanel> cPanels = f.getTabletop().getCardPanels();
                    for (final CardPanel c : cPanels) {
                        if (c.isSelected()) {
                            activePanel = c;
                            break;
                        }
                    }
                }
                if (activePanel == null && activeStackItem == null) { return; }
                break;
            case ON:
                // Draw all
                for (final VField f : matchUI.getFieldViews()) {
                    cardPanels.addAll(f.getTabletop().getCardPanels());
                }
        }

        //final Point docOffsets = FView.SINGLETON_INSTANCE.getLpnDocument().getLocationOnScreen();
        // Locations of arc endpoint, per card, with ID as primary key.
        final Map<Integer, Point> endpoints = new HashMap<>();

        Point cardLocOnScreen;
        Point locOnScreen = this.getPanel().getLocationOnScreen();

        for (CardPanel c : cardPanels) {
            if (c.isShowing() && isInOverlayWindow(c)) {
	            cardLocOnScreen = c.getCardLocationOnScreen();
            endpoints.put(c.getCard().getId(), new Point(
                (int) (cardLocOnScreen.getX() - locOnScreen.getX() + (float)c.getWidth() * CardPanel.TARGET_ORIGIN_FACTOR_X),
	                (int) (cardLocOnScreen.getY() - locOnScreen.getY() + (float)c.getHeight() * CardPanel.TARGET_ORIGIN_FACTOR_Y)
            ));
           }
       }

        if (matchUI.getCDock().getArcState() == ArcState.MOUSEOVER) {
            // Only work with the active panel
            if (activePanel != null) {
                addArcsForCard(activePanel.getCard(), endpoints, combat);
            }
        }
        else {
            // Work with all card panels currently visible
            for (final CardPanel c : cardPanels) {
                if (!c.isShowing()) {
                    continue;
                }
                addArcsForCard(c.getCard(), endpoints, combat);
            }
        }

        //draw arrow connecting active item on stack
        if (activeStackItem != null && isInOverlayWindow(activeStackItem)) {
            Point itemLocOnScreen = activeStackItem.getLocationOnScreen();
            if (itemLocOnScreen != null) {
                itemLocOnScreen.x += StackInstanceTextArea.CARD_WIDTH * CardPanel.TARGET_ORIGIN_FACTOR_X + StackInstanceTextArea.PADDING - locOnScreen.getX();
                itemLocOnScreen.y += StackInstanceTextArea.CARD_HEIGHT * CardPanel.TARGET_ORIGIN_FACTOR_Y + StackInstanceTextArea.PADDING - locOnScreen.getY();
    
                StackItemView instance = activeStackItem.getItem();
                PlayerView activator = instance.getActivatingPlayer();
                while (instance != null) {
                    for (CardView c : instance.getTargetCards()) {
                        addArc(endpoints.get(c.getId()), itemLocOnScreen, activator.isOpponentOf(c.getController()) ?
                                ArcConnection.FoesStackTargeting : ArcConnection.FriendsStackTargeting);
                    }
                    for (PlayerView p : instance.getTargetPlayers()) {
                        Point point = getPlayerTargetingArrowPoint(p, locOnScreen);
                        if(point != null) {
                            addArc(point, itemLocOnScreen, activator.isOpponentOf(p) ?
                                    ArcConnection.FoesStackTargeting : ArcConnection.FriendsStackTargeting);
                        }
                    }
                    instance = instance.getSubInstance();
                }
            }
        }
    }

    // A throttled version of assembleArcs. Though it is still called on every
    // repaint, we take means to avoid it fully running every time (to reduce CPU usage).
    private boolean assembleArcs(final CombatView combat, boolean forceAssemble) {
        if (!this.getPanel().isShowing()) {
            return false;
        }

        if (!forceAssemble) {
            /* -- Minimum update frequency timer, currently disabled --
            long now = System.currentTimeMillis();
            if (now - lastUpdated <= 10) {
                // TODO: Minimum timer needed? How bad are CPU spikes without this on fast machines?
                return false;
            }
            */
            if (allowedUpdates >= MAX_CONSECUTIVE_UPDATES) {
                // Reduce update spam by blocking every Nth attempt (zero-based).
                // N should be adjusted to as low as possible to keep CPU usage low,
                // while being high enough to avoid visual artifacts.
                allowedUpdates = 0;
                return false;
            } else {
                allowedUpdates++;
                //lastUpdated = now; // Uncomment if enabling timer above
            }
        }

        if (!assembler.isListening() && matchUI != null && matchUI.getFieldViews() != null) {
            assembler.setListening(true);
            for (final VField f : matchUI.getFieldViews()) {
                f.getTabletop().addLayoutListener(assembler);
                f.getTabletop().addCardPanelMouseListener(assembler);
            }
        }
        //List<VField> fields = VMatchUI.SINGLETON_INSTANCE.getFieldViews();
        arcsFoeAtk.clear();
        arcsFoeDef.clear();
        arcsFriend.clear();
        cardPanels.clear();
        cardsVisualized.clear();

        try {
            switch (matchUI.getCDock().getArcState()) {
                case OFF:
                    return true;
                case MOUSEOVER:
                    // Draw only hovered card
                    activePanel = null;
                    for (final VField f : matchUI.getFieldViews()) {
                        cardPanels.addAll(f.getTabletop().getCardPanels());
                        final List<CardPanel> cPanels = f.getTabletop().getCardPanels();
                        for (final CardPanel c : cPanels) {
                            if (c.isSelected()) {
                                activePanel = c;
                                break;
                            }
                        }
                    }
                    if (activePanel == null) { return true; }
                    break;
                case ON:
                    // Draw all
                    for (final VField f : matchUI.getFieldViews()) {
                        cardPanels.addAll(f.getTabletop().getCardPanels());
                    }
            }

            //final Point docOffsets = FView.SINGLETON_INSTANCE.getLpnDocument().getLocationOnScreen();
            // Locations of arc endpoint, per card, with ID as primary key.
            final Map<Integer, Point> endpoints = getCardEndpoints();

            if (matchUI.getCDock().getArcState() == ArcState.MOUSEOVER) {
                // Only work with the active panel
                if (activePanel != null) {
                    addArcsForCard(activePanel.getCard(), endpoints, combat);
                }
            }
            else {
                // Work with all card panels currently visible
                for (final CardPanel c : cardPanels) {
                    if (!c.isShowing()) {
                        continue;
                    }
                    addArcsForCard(c.getCard(), endpoints, combat);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    private Map<Integer, Point> getCardEndpoints() {
        final Map<Integer, Point> endpoints = new HashMap<>();

        Point cardLocOnScreen;
        Point locOnScreen = this.getPanel().getLocationOnScreen();

        for (CardPanel c : cardPanels) {
            if (c.isShowing() && isInOverlayWindow(c)) {
	            cardLocOnScreen = c.getCardLocationOnScreen();
	            endpoints.put(c.getCard().getId(), new Point(
	                (int) (cardLocOnScreen.getX() - locOnScreen.getX() + (float)c.getWidth() * CardPanel.TARGET_ORIGIN_FACTOR_X),
	                (int) (cardLocOnScreen.getY() - locOnScreen.getY() + (float)c.getHeight() * CardPanel.TARGET_ORIGIN_FACTOR_Y)
	            ));
            }
        }
        return endpoints;
    }

    // This section is a refactored portion of the new-style assembleArcs.
    private void assembleStackArrows() {
        if (!this.getPanel().isShowing()) {
            return;
        }

        final StackInstanceTextArea activeStackItem = matchUI.getCStack().getView().getHoveredItem();

        if (activeStackItem != null && isInOverlayWindow(activeStackItem)) {
            // Add event listeners to the stack item to repaint on mouse
            // entry/exit, to clear up visual artifacts of our arrows
            if (stackItemIDs.add(activeStackItem.hashCode())) {
                activeStackItem.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(final MouseEvent e) {
                    	if (matchUI.getCDock().getArcState() == ArcState.MOUSEOVER)	{
                    		assembleStackArrows();
                    		FView.SINGLETON_INSTANCE.getFrame().repaint();
                    	}
                    }
                    @Override
                    public void mouseExited(final MouseEvent e) {
                    	if (matchUI.getCDock().getArcState() == ArcState.MOUSEOVER)	{
                    		assembleStackArrows();
                    		FView.SINGLETON_INSTANCE.getFrame().repaint();
                    	}
                    }
                    @Override
                    public void mouseClicked(final MouseEvent e) {
                    	if (matchUI.getCDock().getArcState() == ArcState.ON) {
                    		assembleStackArrows();
                    		FView.SINGLETON_INSTANCE.getFrame().repaint();
                    	}
                    }
                });
            }
            final Map<Integer, Point> endpoints = getCardEndpoints();
            Point locOnScreen = this.getPanel().getLocationOnScreen();
            Point itemLocOnScreen = activeStackItem.getLocationOnScreen();
            if (itemLocOnScreen != null) {
                itemLocOnScreen.x += StackInstanceTextArea.CARD_WIDTH * CardPanel.TARGET_ORIGIN_FACTOR_X + StackInstanceTextArea.PADDING - locOnScreen.getX();
                itemLocOnScreen.y += StackInstanceTextArea.CARD_HEIGHT * CardPanel.TARGET_ORIGIN_FACTOR_Y + StackInstanceTextArea.PADDING - locOnScreen.getY();
    
                StackItemView instance = activeStackItem.getItem();
                PlayerView activator = instance.getActivatingPlayer();
                while (instance != null) {
                    for (CardView c : instance.getTargetCards()) {
                        addArc(endpoints.get(c.getId()), itemLocOnScreen, activator.isOpponentOf(c.getController()) ?
                                ArcConnection.FoesStackTargeting : ArcConnection.FriendsStackTargeting);
                    }
                    for (PlayerView p : instance.getTargetPlayers()) {
                        Point point = getPlayerTargetingArrowPoint(p, locOnScreen);
                        if (point != null) {
                            addArc(point, itemLocOnScreen, activator.isOpponentOf(p) ?
                                    ArcConnection.FoesStackTargeting : ArcConnection.FriendsStackTargeting);
                        }
                    }
                    instance = instance.getSubInstance();
                }
            }
        }
    }

    private static boolean showCombatHighlights() {
        return FModel.getPreferences().getPrefBoolean(FPref.UI_COMBAT_HIGHLIGHTS);
    }

    private static String getPowerLabel(final CardView attacker) {
        if (!showCombatHighlights() || attacker.getCurrentState() == null) {
            return null;
        }
        return String.valueOf(attacker.getCurrentState().getPower());
    }

    /**
     * The overlay only covers the main window: components in a detached window (see SFloatingDocs)
     * are on another surface, possibly another monitor, and must not get arrows.
     */
    private boolean isInOverlayWindow(final Component c) {
        return SwingUtilities.getWindowAncestor(c) == SwingUtilities.getWindowAncestor(pnl);
    }

    private Point getPlayerTargetingArrowPoint(final PlayerView p, final Point locOnScreen) {
        final JPanel avatarArea = matchUI.getFieldViewFor(p).getAvatarArea();
        if(!avatarArea.isShowing() || !isInOverlayWindow(avatarArea)) {
            return null;
        }

        final Point point = avatarArea.getLocationOnScreen();
        point.x += avatarArea.getWidth() / 2 - locOnScreen.x;
        point.y += avatarArea.getHeight() / 2 - locOnScreen.y;
        return point;
    }

    private void addArc(Point end, Point start, ArcConnection connects) {
        addArc(end, start, connects, null);
    }

    private void addArc(Point end, Point start, ArcConnection connects, String label) {
        if (start == null || end == null) {
            return;
        }

        Arc newArc = new Arc(end, start, label);
        
        switch (connects) {
            case Friends:
            case FriendsStackTargeting:
            	if (!arcsFriend.contains(newArc)) {
            		arcsFriend.add(newArc);
            	}
                break;
            case FoesAttacking:
                if (!arcsFoeAtk.contains(newArc)) {
                	arcsFoeAtk.add(newArc);
                }
                break;
            case FoesBlocking:
            case FoesStackTargeting:
            	if (!arcsFoeDef.contains(newArc)) {
            		arcsFoeDef.add(newArc);
            	}
            	break;
        }
    }

    private void addArcsForCard(final CardView c, final Map<Integer, Point> endpoints, final CombatView combat) {
        if (!cardsVisualized.add(c)) {
            return; //don't add arcs for cards if card already visualized
        }

        final CardView attachedTo = c.getAttachedTo();
        final CardView paired = c.getPairedWith();

        if (null != attachedTo) {
            if (attachedTo.getController() != null && !attachedTo.getController().equals(c.getController())) {
                addArc(endpoints.get(attachedTo.getId()), endpoints.get(c.getId()), ArcConnection.Friends);
                cardsVisualized.add(attachedTo);
            }
        }

        for (final CardView enc : c.getAttachedCards()) {
            if (enc.getController() != null && !enc.getController().equals(c.getController())) {
                addArc(endpoints.get(c.getId()), endpoints.get(enc.getId()), ArcConnection.Friends);
                cardsVisualized.add(enc);
            }
        }

        if (null != paired) {
            addArc(endpoints.get(paired.getId()), endpoints.get(c.getId()), ArcConnection.Friends);
            cardsVisualized.add(paired);
        }
        if (null != combat) {
            final GameEntityView defender = combat.getDefender(c);
            // if c is attacking a planeswalker
            if (defender instanceof CardView) {
                addArc(endpoints.get(defender.getId()), endpoints.get(c.getId()), ArcConnection.FoesAttacking, getPowerLabel(c));
            }
            // if c is attacking a player
            if (defender instanceof PlayerView) {
                final JPanel avatarArea = matchUI.getFieldViewFor((PlayerView)defender).getAvatarArea();
                if(avatarArea.isShowing() && isInOverlayWindow(avatarArea)) {
                    Point locOnScreen = this.getPanel().getLocationOnScreen();
                    Point point = getPlayerTargetingArrowPoint((PlayerView)defender, locOnScreen);
                    addArc(point, endpoints.get(c.getId()), ArcConnection.FoesAttacking, getPowerLabel(c));
                }
            }
            // if c is a planeswalker that's being attacked
            for (final CardView pwAttacker : combat.getAttackersOf(c)) {
                addArc(endpoints.get(c.getId()), endpoints.get(pwAttacker.getId()), ArcConnection.FoesAttacking, getPowerLabel(pwAttacker));
            }
            for (final CardView attackingCard : combat.getAttackers()) {
                final Iterable<CardView> cards = combat.getPlannedBlockers(attackingCard);
                if (cards == null) continue;
                for (final CardView blockingCard : cards) {
                    if (!attackingCard.equals(c) && !blockingCard.equals(c)) { continue; }
                    addArc(endpoints.get(attackingCard.getId()), endpoints.get(blockingCard.getId()), ArcConnection.FoesBlocking);
                    cardsVisualized.add(blockingCard);
                    cardsVisualized.add(attackingCard);
                }
            }
        }
    }

    private static final Color ARROW_SHADOW = new Color(0, 0, 0, 90);
    private static final Color ARROW_OUTLINE = new Color(0, 0, 0, 140);
    private static final Color INCOMING_DAMAGE_COLOR = new Color(210, 40, 40);

    private class OverlayPanel extends SkinnedPanel {
        private final boolean useThrottling = FModel.getPreferences().getPrefBoolean(FPref.UI_TIMED_TARGETING_OVERLAY_UPDATES);

        // Arrow drawing code by the XMage team, used with permission.
        private Area getArrow(float length, float bendPercent) {
            float p1x = 0, p1y = 0;
            float p2x = length, p2y = 0;
            float cx = length / 2, cy = length / 8f * bendPercent;

            int bodyWidth = 10;
            float headSize = 24;

            float adjSize, ex, ey, abs_e;
            adjSize = (float) (bodyWidth / 2 / Math.sqrt(2));
            ex = p2x - cx;
            ey = p2y - cy;
            abs_e = (float) Math.sqrt(ex * ex + ey * ey);
            ex /= abs_e;
            ey /= abs_e;
            GeneralPath bodyPath = new GeneralPath();
            bodyPath.moveTo(p2x + (ey - ex) * adjSize, p2y - (ex + ey) * adjSize);
            bodyPath.quadTo(cx, cy, p1x, p1y - bodyWidth / 2);
            bodyPath.lineTo(p1x, p1y + bodyWidth / 2);
            bodyPath.quadTo(cx, cy, p2x - (ey + ex) * adjSize, p2y + (ex - ey) * adjSize);
            bodyPath.closePath();

            adjSize = (float) (headSize / Math.sqrt(2));
            ex = p2x - cx;
            ey = p2y - cy;
            abs_e = (float) Math.sqrt(ex * ex + ey * ey);
            ex /= abs_e;
            ey /= abs_e;
            GeneralPath headPath = new GeneralPath();
            headPath.moveTo(p2x - (ey + ex) * adjSize, p2y + (ex - ey) * adjSize);
            headPath.lineTo(p2x + headSize / 2, p2y);
            headPath.lineTo(p2x + (ey - ex) * adjSize, p2y - (ex + ey) * adjSize);
            headPath.closePath();

            Area area = new Area(headPath);
            area.add(new Area(bodyPath));
            return area;
        }

        private void drawArrow(Graphics2D g2d, int startX, int startY, int endX, int endY, Color color) {
            float ex = endX - startX;
            float ey = endY - startY;
            if (ex == 0 && ey == 0) { return; }

            float length = (float) Math.sqrt(ex * ex + ey * ey);
            float bendPercent = (float) Math.asin(ey / length);

            if (endX > startX) {
                bendPercent = -bendPercent;
            }

            Area arrow = getArrow(length, bendPercent);
            AffineTransform af = g2d.getTransform();

            g2d.translate(startX + 2, startY + 3);
            g2d.rotate(Math.atan2(ey, ex));
            g2d.setColor(ARROW_SHADOW); //soft drop shadow instead of a hard black outline
            g2d.fill(arrow);
            g2d.setTransform(af);

            g2d.translate(startX, startY);
            g2d.rotate(Math.atan2(ey, ex));
            g2d.setColor(color);
            g2d.fill(arrow);
            g2d.setColor(ARROW_OUTLINE);
            g2d.setStroke(new BasicStroke(1.2f));
            g2d.draw(arrow);

            g2d.setTransform(af);
        }

        private void drawArcs(Graphics2D g2d, Color color, List<Arc> arcs) {
            for (Arc arc : arcs) {
                drawArrow(g2d, arc.x1, arc.y1, arc.x2, arc.y2, color);
            }
            for (Arc arc : arcs) {
                if (arc.label != null) {
                    //badge at 60% of the way, closer to the target to stay clear of the attacking card
                    drawBadge(g2d, arc.x1 + (arc.x2 - arc.x1) * 3 / 5, arc.y1 + (arc.y2 - arc.y1) * 3 / 5,
                            arc.label, opaque(color), 11);
                }
            }
        }

        /** Round badge with centered bold text, e.g. an attacker's power or the damage a player is about to take. */
        private void drawBadge(Graphics2D g2d, int centerX, int centerY, String text, Color fill, int fontSize) {
            g2d.setFont(g2d.getFont().deriveFont(Font.BOLD, (float) fontSize));
            final FontMetrics fm = g2d.getFontMetrics();
            final int h = fm.getHeight() + 4;
            final int w = Math.max(h, fm.stringWidth(text) + 12);
            final int x = centerX - w / 2;
            final int y = centerY - h / 2;

            g2d.setColor(ARROW_SHADOW);
            g2d.fillRoundRect(x + 1, y + 2, w, h, h, h);
            g2d.setColor(fill);
            g2d.fillRoundRect(x, y, w, h, h, h);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(1.5f));
            g2d.drawRoundRect(x, y, w, h, h, h);
            g2d.drawString(text, centerX - fm.stringWidth(text) / 2, y + (h - fm.getHeight()) / 2 + fm.getAscent());
        }

        /**
         * Shows on each attacked player's avatar the damage they take if nothing else changes:
         * total power of their unblocked attackers (an estimate: ignores trample, double strike, prevention...).
         */
        private void drawIncomingDamage(Graphics2D g2d, CombatView combat, Color color) {
            final Point locOnScreen = getPanel().getLocationOnScreen();
            for (final GameEntityView defender : combat.getDefenders()) {
                if (!(defender instanceof PlayerView player)) {
                    continue;
                }
                int damage = 0;
                for (final CardView attacker : combat.getAttackersOf(defender)) {
                    if (isUnblocked(combat, attacker) && attacker.getCurrentState() != null) {
                        damage += Math.max(0, attacker.getCurrentState().getPower());
                    }
                }
                if (damage <= 0) {
                    continue;
                }
                final Point point = getPlayerTargetingArrowPoint(player, locOnScreen);
                if (point != null) {
                    drawBadge(g2d, point.x, point.y, "-" + damage, color, 18);
                }
            }
        }

        private boolean isUnblocked(CombatView combat, CardView attacker) {
            final Iterable<CardView> blockers = combat.getBlockers(attacker);
            final Iterable<CardView> planned = combat.getPlannedBlockers(attacker);
            return (blockers == null || !blockers.iterator().hasNext()) && (planned == null || !planned.iterator().hasNext());
        }

        private Color opaque(Color c) {
            return new Color(c.getRed(), c.getGreen(), c.getBlue());
        }

        /**
         * For some reason, the alpha channel background doesn't work properly on
         * Windows 7, so the paintComponent override is required for a
         * semi-transparent overlay.
         * 
         * @param g
         *            &emsp; Graphics object
         */
        @Override
        public void paintComponent(final Graphics g) {
            // No need for this except in match view
            if (Singletons.getControl().getCurrentScreen() != matchUI.getScreen()) {
                return;
            }

            super.paintComponent(g);

            final ArcState overlaystate = matchUI.getCDock().getArcState();

            // Arcs are off
            if (overlaystate == ArcState.OFF) {
                paintIncomingDamage(g);
                return;
            }

            // Arc drawing
            boolean assembled = false;
            final GameView gameView = matchUI.getGameView();
            if (gameView != null) {
                if (useThrottling) {
                    assembled = assembleArcs(gameView.getCombat(), false);
                    assembleStackArrows();
                } else {
                    assembleArcs(gameView.getCombat());
                }
            }

            if (arcsFoeAtk.isEmpty() && arcsFoeDef.isEmpty() && arcsFriend.isEmpty()) {
                if (assembled) {
                    // We still need to repaint to get rid of visual artifacts
                    // The original (non-throttled) code did not do this repaint.
                    FView.SINGLETON_INSTANCE.getFrame().repaint();
                }
                paintIncomingDamage(g);
                return;
            }

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Get arrow colors from the theme or use default colors if the theme does not have them defined
            Color colorOther = FSkin.getColor(FSkin.Colors.CLR_NORMAL_TARGETING_ARROW).getColor();
            if (colorOther.getAlpha() == 0) {
                colorOther = FSkin.getColor(FSkin.Colors.CLR_ACTIVE).alphaColor(153).getColor();
            }
            Color colorCombat = FSkin.getColor(FSkin.Colors.CLR_COMBAT_TARGETING_ARROW).getColor();
            if (colorCombat.getAlpha() == 0) {
                colorCombat = new Color(255, 0, 0, 153); 
            }
            Color colorCombatAtk = FSkin.getColor(FSkin.Colors.CLR_PWATTK_TARGETING_ARROW).getColor();
            if (colorCombatAtk.getAlpha() == 0) {
                colorCombatAtk = new Color(255,138,1,153);
            }

            drawArcs(g2d, colorOther, arcsFriend);
            drawArcs(g2d, colorCombatAtk, arcsFoeAtk);
            drawArcs(g2d, colorCombat, arcsFoeDef);
            paintIncomingDamage(g);

            if (assembled || !useThrottling) {
                FView.SINGLETON_INSTANCE.getFrame().repaint(); // repaint the match UI
            }
        }

        private void paintIncomingDamage(final Graphics g) {
            final GameView gameView = matchUI.getGameView();
            if (!showCombatHighlights() || gameView == null || gameView.getCombat() == null) {
                return;
            }
            final Graphics2D g2d = (Graphics2D) g.create();
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                drawIncomingDamage(g2d, gameView.getCombat(), INCOMING_DAMAGE_COLOR);
            } finally {
                g2d.dispose();
            }
        }
    }

    // A class for listening to CardPanelContainer events, so that we can redraw arcs in response
    class ArcAssembler implements CardPanelContainer.LayoutEventListener, CardPanelMouseListener {
        // An "initialized"-type variable is needed since we can't initialize on construction
        // (because CardPanelContainers aren't ready at that point)
	    private boolean isListening = false;
        private boolean isDragged = false;
	    public boolean isListening() { return isListening; }
	    public void setListening(boolean listening) { isListening = listening; }

        private void assembleAndRepaint() {
            if (isDragged) { return; }

            final GameView gameView = matchUI.getGameView();
            if (gameView != null) {
                assembleArcs(gameView.getCombat(), true); // Force update despite timer
                FView.SINGLETON_INSTANCE.getFrame().repaint(); // repaint the match UI
            }
        }
        @Override
        public void doingLayout() {
            assembleAndRepaint();
        }
        @Override
        public void mouseOver(CardPanel panel, MouseEvent evt) {
            assembleAndRepaint();
        }
        @Override
        public void mouseOut(CardPanel panel, MouseEvent evt) {
            assembleAndRepaint();
        }

        // Do not aggressively assemble/repaint when dragging around card panels
        @Override
        public void mouseDragStart(CardPanel dragPanel, MouseEvent evt) { isDragged = true; }
        @Override
        public void mouseDragEnd(CardPanel dragPanel, MouseEvent evt) { isDragged = false; }

        // We don't need the other mouse events the interface provides; stub them out
        @Override
        public void mouseLeftClicked(CardPanel panel, MouseEvent evt) {}
        @Override
        public void mouseRightClicked(CardPanel panel, MouseEvent evt) {}
        @Override
        public void mouseDragged(CardPanel dragPanel, int dragOffsetX, int dragOffsetY, MouseEvent evt) {}
    }
}
