/*
 * Copyright (c) 2026, SeismicRy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package geoffertimer;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GETimerOverlay extends Overlay
{
    private static final int GE_SLOTS = 8;

    private final GEOfferTimerPlugin plugin;
    private final Client client;
    private final ItemManager itemManager;
    private final GEOfferTimerConfig config;
    private final PanelComponent panelComponent = new PanelComponent();

    private static final Color BUY_UNFILLED = new Color(0, 180, 0);
    private static final Color BUY_PARTIAL = Color.GREEN;
    private static final Color BUY_COMPLETE = Color.GREEN;
    private static final Color SELL_UNFILLED = new Color(180, 140, 0);
    private static final Color SELL_PARTIAL = Color.YELLOW;
    private static final Color SELL_COMPLETE = Color.YELLOW;
    private static final Color CANCELLED_COLOR = Color.RED;

    @Inject
    public GETimerOverlay(GEOfferTimerPlugin plugin, Client client, ItemManager itemManager, GEOfferTimerConfig config)
    {
        this.plugin = plugin;
        this.client = client;
        this.itemManager = itemManager;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        // If the setting is on, only show when the GE window is open.
        if (config.onlyShowAtGE())
        {
            Widget geWidget = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
            if (geWidget == null || geWidget.isHidden())
            {
                return null;
            }
        }

        GrandExchangeOffer[] liveOffers = client.getGrandExchangeOffers();

        List<LineComponent> activeLines = new ArrayList<>();
        List<LineComponent> completedLines = new ArrayList<>();

        for (int slot = 0; slot < GE_SLOTS; slot++)
        {
            GrandExchangeOffer offer = (liveOffers != null && slot < liveOffers.length) ? liveOffers[slot] : null;

            // The live offer is the source of truth for whether a slot still has
            // anything in it. If it's empty (collected, or just not synced yet
            // after a login/hop) we skip it, so stale tracked data can never
            // render as a "? Slot N" or already-collected ghost line.
            if (offer == null || offer.getItemId() == 0 || offer.getState() == GrandExchangeOfferState.EMPTY)
            {
                continue;
            }

            GrandExchangeOfferState state = offer.getState();

            // Active offer
            Instant start = plugin.offerStartTimes.get(slot);
            if (start != null
                    && (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING))
            {
                boolean isBuying = state == GrandExchangeOfferState.BUYING;
                int filled = offer.getQuantitySold();
                int total = offer.getTotalQuantity();
                Color color = isBuying
                        ? (filled == 0 ? BUY_UNFILLED : BUY_PARTIAL)
                        : (filled == 0 ? SELL_UNFILLED : SELL_PARTIAL);

                String name = itemManager.getItemComposition(offer.getItemId()).getName();
                String time = formatDuration(Duration.between(start, Instant.now()).toMillis());

                activeLines.add(LineComponent.builder()
                        .left((isBuying ? "BUY" : "SELL") + "  " + name + " (" + filled + "/" + total + ")")
                        .leftColor(color)
                        .right(time)
                        .rightColor(color)
                        .build());
                continue;
            }

            // Completed / cancelled offer
            Long completed = plugin.completedOfferTimes.get(slot);
            if (completed != null)
            {
                String stateLabel = plugin.completedOfferStates.getOrDefault(slot, "DONE");

                Integer itemId = plugin.completedOfferItems.get(slot);
                int resolvedItem = (itemId != null && itemId != 0) ? itemId : offer.getItemId();
                String name = itemManager.getItemComposition(resolvedItem).getName();

                Color labelColor;
                if (stateLabel.equals("BOUGHT"))
                {
                    labelColor = BUY_COMPLETE;
                }
                else if (stateLabel.equals("SOLD"))
                {
                    labelColor = SELL_COMPLETE;
                }
                else
                {
                    labelColor = CANCELLED_COLOR;
                }

                completedLines.add(LineComponent.builder()
                        .left(stateLabel + "  " + name)
                        .leftColor(labelColor)
                        .right(formatDuration(completed) + " ✓")
                        .rightColor(Color.WHITE)
                        .build());
            }
        }

        if (activeLines.isEmpty() && completedLines.isEmpty())
        {
            return null;
        }

        panelComponent.getChildren().clear();
        panelComponent.setPreferredSize(new Dimension(280, 0));

        panelComponent.getChildren().add(TitleComponent.builder()
                .text("GE Offer Timers")
                .color(Color.WHITE)
                .build());

        panelComponent.getChildren().addAll(activeLines);

        // Blank spacer between the active and completed sections.
        if (!activeLines.isEmpty() && !completedLines.isEmpty())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("")
                    .build());
        }

        panelComponent.getChildren().addAll(completedLines);

        return panelComponent.render(graphics);
    }

    private String formatDuration(long millis)
    {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}
