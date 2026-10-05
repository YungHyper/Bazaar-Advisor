package com.example.client;

import com.example.client.FlipEngine.AuctionFlip;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oversized click targets for the BIN buy and confirm screens. Each mouse click sends exactly one normal slot click. */
final class QuickBuy {
    static boolean enabled = true;
    static BiFunction<String, Double, AuctionFlip> flipLookup = (name, price) -> null;

    private static final long CLICK_GRACE_MS = 200;
    private static final Pattern COINS = Pattern.compile("([\\d,]{4,})\\s*coins", Pattern.CASE_INSENSITIVE);

    private record Layout(int left, int top, int width, int height, int bx0, int by0, int bx1, int by1,
                          int cx0, int cy0, int cx1, int cy1) {}

    private record Targets(int primary, int cancel, boolean confirmScreen) {}

    private QuickBuy() {}

    static void attach(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return;
        String title = screen.getTitle().getString().toLowerCase();
        boolean confirmScreen = title.contains("confirm purchase");
        if (!confirmScreen && !title.contains("bin auction view")) return;
        long opened = System.currentTimeMillis();
        ScreenEvents.afterExtract(screen).register((current, context, mouseX, mouseY, delta) -> {
            if (!enabled) return;
            Targets targets = findTargets(container.getMenu(), confirmScreen);
            if (targets != null) draw(context, screen, container.getMenu(), targets, mouseX, mouseY);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> {
            if (!enabled) return true;
            Targets targets = findTargets(container.getMenu(), confirmScreen);
            if (targets == null) return true;
            if (event.button() == 0 && System.currentTimeMillis() - opened >= CLICK_GRACE_MS) {
                Layout layout = layout(screen.width, screen.height, targets.cancel() >= 0);
                if (inside(event.x(), event.y(), layout.bx0(), layout.by0(), layout.bx1(), layout.by1())) {
                    clickSlot(container.getMenu(), targets.primary());
                } else if (targets.cancel() >= 0 && inside(event.x(), event.y(), layout.cx0(), layout.cy0(), layout.cx1(), layout.cy1())) {
                    clickSlot(container.getMenu(), targets.cancel());
                }
            }
            return false;
        });
    }

    private static Targets findTargets(AbstractContainerMenu menu, boolean confirmScreen) {
        int primary = confirmScreen ? findSlot(menu, "confirm") : findSlot(menu, "buy item right now");
        if (primary < 0) return null;
        return new Targets(primary, confirmScreen ? findSlot(menu, "cancel") : -1, confirmScreen);
    }

    private static int findSlot(AbstractContainerMenu menu, String name) {
        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (!stack.isEmpty() && stack.getHoverName().getString().trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static void clickSlot(AbstractContainerMenu menu, int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null || client.player == null) return;
        client.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, client.player);
    }

    private static boolean inside(double x, double y, int x0, int y0, int x1, int y1) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    private static Layout layout(int screenWidth, int screenHeight, boolean hasCancel) {
        int width = Math.min(380, screenWidth - 20);
        int height = Math.min(170, screenHeight - 20);
        int left = (screenWidth - width) / 2;
        int top = (screenHeight - height) / 2;
        int bodyTop = top + 46;
        int bodyBottom = top + height - 8;
        int primaryRight = hasCancel ? left + (int) (width * 0.70) : left + width - 8;
        return new Layout(left, top, width, height, left + 8, bodyTop, primaryRight, bodyBottom,
            primaryRight + 8, bodyTop, left + width - 8, bodyBottom);
    }

    private static AuctionFlip matchFlip(AbstractContainerMenu menu, Targets targets) {
        if (menu.slots.size() <= 13) return null;
        ItemStack shown = menu.slots.get(13).getItem();
        if (shown.isEmpty()) return null;
        return flipLookup.apply(shown.getHoverName().getString().trim(), priceOf(menu.slots.get(targets.primary()).getItem()));
    }

    private static double priceOf(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) {
                Matcher matcher = COINS.matcher(line.getString());
                if (matcher.find()) return Double.parseDouble(matcher.group(1).replace(",", ""));
            }
        } catch (RuntimeException ignored) {
            // Tooltip parsing is best-effort; the overlay still works without a price match.
        }
        return -1;
    }

    private static void draw(GuiGraphicsExtractor context, Screen screen, AbstractContainerMenu menu, Targets targets, int mouseX, int mouseY) {
        context.nextStratum();
        var font = Minecraft.getInstance().font;
        Layout layout = layout(screen.width, screen.height, targets.cancel() >= 0);
        AuctionFlip flip = matchFlip(menu, targets);
        context.fill(0, 0, screen.width, screen.height, 0xC0000000);
        context.fill(layout.left(), layout.top(), layout.left() + layout.width(), layout.top() + layout.height(), 0xF0182233);
        context.fill(layout.left(), layout.top(), layout.left() + layout.width(), layout.top() + 2, 0xFFFBBF24);

        int centerX = layout.left() + layout.width() / 2;
        String itemName = menu.slots.size() > 13 && !menu.slots.get(13).getItem().isEmpty()
            ? menu.slots.get(13).getItem().getHoverName().getString() : "Auction";
        context.centeredText(font, Component.literal(itemName).withStyle(ChatFormatting.BOLD), centerX, layout.top() + 8, 0xFFFFFFFF);
        if (flip != null) {
            context.centeredText(font, Component.literal("BUY " + coins(flip.buyPrice()) + "   NET +" + coins(flip.estimatedProfit())
                + "   " + flip.recentSaleCount() + " sales   " + (flip.exact() ? "EXACT" : "~CORE")), centerX, layout.top() + 21, 0xFF7CF29A);
            long left = (flip.endTime() - System.currentTimeMillis()) / 1000;
            if (left <= 0) context.centeredText(font, Component.literal("AUCTION ENDED").withStyle(ChatFormatting.BOLD), centerX, layout.top() + 33, 0xFFFF5555);
            else if (left <= 15) context.centeredText(font, Component.literal("Ends in " + left + "s"), centerX, layout.top() + 33, 0xFFFFD166);
        } else {
            context.centeredText(font, Component.literal("Check the price before confirming"), centerX, layout.top() + 21, 0xFFB8C4D6);
        }

        boolean primaryHover = inside(mouseX, mouseY, layout.bx0(), layout.by0(), layout.bx1(), layout.by1());
        context.fill(layout.bx0(), layout.by0(), layout.bx1(), layout.by1(), primaryHover ? 0xFF3FD068 : 0xFF2E9E4F);
        bigLabel(context, font, targets.confirmScreen() ? "CONFIRM PURCHASE" : "BUY NOW",
            (layout.bx0() + layout.bx1()) / 2, (layout.by0() + layout.by1()) / 2);
        if (targets.cancel() >= 0) {
            boolean cancelHover = inside(mouseX, mouseY, layout.cx0(), layout.cy0(), layout.cx1(), layout.cy1());
            context.fill(layout.cx0(), layout.cy0(), layout.cx1(), layout.cy1(), cancelHover ? 0xFFE05A5A : 0xFFA83A3A);
            context.centeredText(font, Component.literal("Cancel"), (layout.cx0() + layout.cx1()) / 2,
                (layout.cy0() + layout.cy1()) / 2 - 4, 0xFFFFFFFF);
        }
    }

    private static void bigLabel(GuiGraphicsExtractor context, net.minecraft.client.gui.Font font, String text, int centerX, int centerY) {
        context.pose().pushMatrix();
        context.pose().translate(centerX, centerY);
        context.pose().scale(2.0f, 2.0f);
        context.centeredText(font, Component.literal(text).withStyle(ChatFormatting.BOLD), 0, -4, 0xFFFFFFFF);
        context.pose().popMatrix();
    }

    private static String coins(double value) {
        if (value >= 1_000_000) return "%.1fm".formatted(value / 1_000_000);
        if (value >= 1_000) return "%.1fk".formatted(value / 1_000);
        return "%.0f".formatted(value);
    }
}
