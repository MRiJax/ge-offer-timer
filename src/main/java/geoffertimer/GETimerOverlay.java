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
import java.util.Map;

public class GETimerOverlay extends Overlay
{
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
        // If setting is on, only show when GE is open
        if (config.onlyShowAtGE())
        {
            Widget geWidget = client.getWidget(ComponentID.GRAND_EXCHANGE_WINDOW_CONTAINER);
            if (geWidget == null || geWidget.isHidden())
            {
                return null;
            }
        }

        Map<Integer, Instant> offerStartTimes = plugin.offerStartTimes;
        Map<Integer, Long> completedOfferTimes = plugin.completedOfferTimes;
        Map<Integer, String> completedOfferStates = plugin.completedOfferStates;
        Map<Integer, Integer> completedOfferItems = plugin.completedOfferItems;

        if (offerStartTimes.isEmpty() && completedOfferTimes.isEmpty())
        {
            return null;
        }

        panelComponent.getChildren().clear();
        panelComponent.setPreferredSize(new Dimension(280, 0));

        panelComponent.getChildren().add(TitleComponent.builder()
                .text("GE Offer Timers")
                .color(Color.WHITE)
                .build());

        // Active offers
        for (Map.Entry<Integer, Instant> entry : offerStartTimes.entrySet())
        {
            int slot = entry.getKey();
            Duration duration = Duration.between(entry.getValue(), Instant.now());
            String timeString = formatDuration(duration.toMillis());

            GrandExchangeOffer offer = client.getGrandExchangeOffers()[slot];
            String itemName = "Slot " + (slot + 1);
            String typeLabel = "?";
            Color color = Color.WHITE;

            if (offer != null && offer.getItemId() != 0)
            {
                itemName = itemManager.getItemComposition(offer.getItemId()).getName();
                boolean isBuying = offer.getState() == GrandExchangeOfferState.BUYING;
                typeLabel = isBuying ? "BUY" : "SELL";

                int filled = offer.getQuantitySold();
                int total = offer.getTotalQuantity();

                if (isBuying)
                {
                    color = filled == 0 ? BUY_UNFILLED : BUY_PARTIAL;
                }
                else
                {
                    color = filled == 0 ? SELL_UNFILLED : SELL_PARTIAL;
                }

                itemName = itemName + " (" + filled + "/" + total + ")";
            }

            panelComponent.getChildren().add(LineComponent.builder()
                    .left(typeLabel + "  " + itemName)
                    .leftColor(color)
                    .right(timeString)
                    .rightColor(color)
                    .build());
        }

        // Space between active and completed
        if (!offerStartTimes.isEmpty() && !completedOfferTimes.isEmpty())
        {
            panelComponent.getChildren().add(LineComponent.builder()
                    .left("")
                    .build());
        }

        // Completed offers
        for (Map.Entry<Integer, Long> entry : completedOfferTimes.entrySet())
        {
            int slot = entry.getKey();
            String timeString = formatDuration(entry.getValue());
            String state = completedOfferStates.getOrDefault(slot, "DONE");
            String itemName = "Slot " + (slot + 1);

            Integer itemId = completedOfferItems.get(slot);
            if (itemId != null && itemId != 0)
            {
                itemName = itemManager.getItemComposition(itemId).getName();
            }

            Color labelColor;
            if (state.equals("BOUGHT"))
            {
                labelColor = BUY_COMPLETE;
            }
            else if (state.equals("SOLD"))
            {
                labelColor = SELL_COMPLETE;
            }
            else
            {
                labelColor = CANCELLED_COLOR;
            }

            panelComponent.getChildren().add(LineComponent.builder()
                    .left(state + "  " + itemName)
                    .leftColor(labelColor)
                    .right(timeString + " ✓")
                    .rightColor(Color.WHITE)
                    .build());
        }

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