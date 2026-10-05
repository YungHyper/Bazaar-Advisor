package com.example.client;

import com.example.client.FlipEngine.AuctionFlip;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oversized click targets for the BIN buy and confirm screens. Each mouse click sends exactly one normal slot click. */
final class QuickBuy {
    static boolean enabled = true;
    static BiFunction<String, Double, AuctionFlip> flipLookup = (name, price) -> null;

    private static final long CLICK_GRACE_MS = 200;
    private static final long REFRESH_MS = 150;
    private static final Pattern COINS = Pattern.compile("([\\d,]{4,})\\s*coins", Pattern.CASE_INSENSITIVE);

    private record Layout(int left, int top, int width, int height, int bx0, int by0, int bx1, int by1,
                          int cx0, int cy0, int cx1, int cy1) {}

    private record Targets(int primary, int cancel, boolean confirmScreen) {}

    private static final class View {
        final long opened = System.currentTimeMillis();
        long refreshedAt;
        Targets targets;
        AuctionFlip flip;
        boolean dumped;
    }

    private QuickBuy() {}

    static void attach(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return;
        String title = screen.getTitle().getString().toLowerCase();
        boolean confirmScreen = title.contains("confirm purchase");
        if (!confirmScreen && !title.contains("bin auction view")) return;
        View view = new View();
        ScreenEvents.afterExtract(screen).register((current, context, mouseX, mouseY, delta) -> {
            if (!enabled) return;
            refresh(view, container.getMenu(), confirmScreen, title, false);
            if (view.targets != null) draw(context, screen, container.getMenu(), view, mouseX, mouseY);
        });
        ScreenMouseEvents.allowMouseClick(screen).register((current, event) -> {
            if (!enabled) return true;
            refresh(view, container.getMenu(), confirmScreen, title, true);
            Targets targets = view.targets;
            if (targets == null) return true;
            if (event.button() == 0 && System.currentTimeMillis() - view.opened >= CLICK_GRACE_MS) {
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

    private static void refresh(View view, AbstractContainerMenu menu, boolean confirmScreen, String title, boolean immediate) {
        long now = System.currentTimeMillis();
        if (!immediate && now - view.refreshedAt < REFRESH_MS) return;
        view.refreshedAt = now;
        Targets targets = findTargets(menu, confirmScreen);
        boolean own = targets != null && isOwnAuction(menu, targets);
        if (!view.dumped && now - view.opened > 400) {
            view.dumped = true;
            dump(menu, title, targets, own);
        }
        if (targets == null || own) {
            view.targets = null;
            view.flip = null;
            return;
        }
        view.targets = targets;
        view.flip = matchFlip(menu, targets);
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

    /** Own auctions (cancel/collect flows) must keep the vanilla screen so nothing blocks cancelling them. */
    private static boolean isOwnAuction(AbstractContainerMenu menu, Targets targets) {
        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().toLowerCase();
            if (name.contains("cancel auction") || name.contains("collect auction")) return true;
        }
        String text = tooltip(menu.slots.size() > 13 ? menu.slots.get(13).getItem() : ItemStack.EMPTY)
            + "\n" + tooltip(menu.slots.get(targets.primary()).getItem());
        if (text.contains("your own") || text.contains("own auction") || text.contains("cancel auction") || text.contains("cancel your")) return true;
        Minecraft client = Minecraft.getInstance();
        String me = client.player == null ? "" : client.player.getName().getString().toLowerCase();
        int seller = text.indexOf("seller:");
        return seller >= 0 && !me.isEmpty() && text.substring(seller, Math.min(text.length(), seller + 64)).contains(me);
    }

    private static String tooltip(ItemStack stack) {
        if (stack.isEmpty()) return "";
        Minecraft client = Minecraft.getInstance();
        StringBuilder text = new StringBuilder();
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.EMPTY, client.player, TooltipFlag.NORMAL)) {
                text.append(line.getString().toLowerCase()).append('\n');
            }
        } catch (RuntimeException ignored) {
            // Tooltip text is best-effort.
        }
        return text.toString();
    }

    private static void dump(AbstractContainerMenu menu, String title, Targets targets, boolean own) {
        StringBuilder out = new StringBuilder("title=" + title + " targets=" + targets + " ownAuction=" + own + "\n");
        int containerSlots = menu.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String lore = tooltip(stack).replace('\n', '|');
            out.append(i).append(" | ").append(stack.getHoverName().getString()).append(" | ")
                .append(lore, 0, Math.min(lore.length(), 400)).append('\n');
        }
        try {
            Path path = FabricLoader.getInstance().getConfigDir().resolve("bazaar-advisor").resolve("quickbuy-debug.txt");
            Files.createDirectories(path.getParent());
            Files.writeString(path, out.toString());
        } catch (IOException ignored) {
            // Debug output is optional.
        }
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
        int width = Math.min(460, screenWidth - 20);
        int height = Math.min(250, screenHeight - 20);
        int left = (screenWidth - width) / 2;
        int top = (screenHeight - height) / 2;
        int bodyTop = top + 92;
        int bodyBottom = top + height - 14;
        int primaryRight = hasCancel ? left + (int) (width * 0.72) : left + width - 14;
        return new Layout(left, top, width, height, left + 14, bodyTop, primaryRight, bodyBottom,
            primaryRight + 10, bodyTop, left + width - 14, bodyBottom);
    }

    private static AuctionFlip matchFlip(AbstractContainerMenu menu, Targets targets) {
        if (menu.slots.size() <= 13) return null;
        ItemStack shown = menu.slots.get(13).getItem();
        if (shown.isEmpty()) return null;
        return flipLookup.apply(shown.getHoverName().getString().trim(), priceOf(menu.slots.get(targets.primary()).getItem()));
    }

    private static double priceOf(ItemStack stack) {
        Matcher matcher = COINS.matcher(tooltip(stack));
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // Falls through to "unknown price".
            }
        }
        return -1;
    }

    private static void draw(GuiGraphicsExtractor context, Screen screen, AbstractContainerMenu menu, View view, int mouseX, int mouseY) {
        context.nextStratum();
        Font font = Minecraft.getInstance().font;
        Targets targets = view.targets;
        AuctionFlip flip = view.flip;
        Layout layout = layout(screen.width, screen.height, targets.cancel() >= 0);
        int centerX = layout.left() + layout.width() / 2;
        int right = layout.left() + layout.width();
        int bottom = layout.top() + layout.height();

        context.fill(0, 0, screen.width, screen.height, 0xB0060912);
        roundRect(context, layout.left() + 3, layout.top() + 4, right + 3, bottom + 4, 12, 0x66000000);
        roundRect(context, layout.left(), layout.top(), right, bottom, 12, 0xFF2B3A5C);
        roundRect(context, layout.left() + 1, layout.top() + 1, right - 1, bottom - 1, 11, 0xFF141B2D);

        String itemName = menu.slots.size() > 13 && !menu.slots.get(13).getItem().isEmpty()
            ? menu.slots.get(13).getItem().getHoverName().getString() : "Auction";
        context.centeredText(font, Component.literal(itemName).withStyle(ChatFormatting.BOLD), centerX, layout.top() + 10, 0xFFFFFFFF);
        if (flip != null) {
            scaledText(context, font, "+" + coins(flip.estimatedProfit()), centerX, layout.top() + 36, 2.2f, 0xFF4ADE80, true);
            double roi = flip.estimatedProfit() / flip.buyPrice() * 100;
            context.centeredText(font, Component.literal("projected profit after fees  -  %.0f%% return".formatted(roi)), centerX, layout.top() + 53, 0xFF93A4C3);
            context.centeredText(font, Component.literal("BUY " + coins(flip.buyPrice()) + "  >  SELL ~" + coins(flip.realizedSalePrice())
                + "   " + flip.recentSaleCount() + " sales   " + (flip.exact() ? "EXACT" : "~CORE")), centerX, layout.top() + 66, 0xFFCBD5E8);
            long left = (flip.endTime() - System.currentTimeMillis()) / 1000;
            if (left <= 0) context.centeredText(font, Component.literal("AUCTION ENDED").withStyle(ChatFormatting.BOLD), centerX, layout.top() + 79, 0xFFFF6B6B);
            else if (left <= 15) context.centeredText(font, Component.literal("Ends in " + left + "s"), centerX, layout.top() + 79, 0xFFFFD166);
        } else {
            context.centeredText(font, Component.literal("No sale-history estimate for this item"), centerX, layout.top() + 38, 0xFF93A4C3);
            context.centeredText(font, Component.literal("Check the price before you confirm"), centerX, layout.top() + 53, 0xFF93A4C3);
        }

        boolean primaryHover = inside(mouseX, mouseY, layout.bx0(), layout.by0(), layout.bx1(), layout.by1());
        button(context, layout.bx0(), layout.by0(), layout.bx1(), layout.by1(), primaryHover ? 0xFF2FCB6E : 0xFF22A559);
        String label = targets.confirmScreen() ? "CONFIRM PURCHASE" : "BUY NOW";
        int buttonCenterX = (layout.bx0() + layout.bx1()) / 2;
        int buttonCenterY = (layout.by0() + layout.by1()) / 2;
        float scale = Math.min(3.0f, (layout.bx1() - layout.bx0() - 28f) / Math.max(1, font.width(label)));
        scaledText(context, font, label, buttonCenterX, buttonCenterY - 4, scale, 0xFFFFFFFF, true);
        String sub = flip != null ? "pay " + coins(flip.buyPrice()) + " coins" : targets.confirmScreen() ? "one click to buy" : "opens the confirm screen";
        context.centeredText(font, Component.literal(sub), buttonCenterX, buttonCenterY + (int) (scale * 5) + 2, 0xFFD6F5E1);

        if (targets.cancel() >= 0) {
            boolean cancelHover = inside(mouseX, mouseY, layout.cx0(), layout.cy0(), layout.cx1(), layout.cy1());
            button(context, layout.cx0(), layout.cy0(), layout.cx1(), layout.cy1(), cancelHover ? 0xFFD0505C : 0xFF9B3B45);
            scaledText(context, font, "CANCEL", (layout.cx0() + layout.cx1()) / 2, (layout.cy0() + layout.cy1()) / 2, 1.3f, 0xFFFFFFFF, true);
        }
    }

    private static void button(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, int color) {
        roundRect(context, x0 + 1, y0 + 3, x1 + 1, y1 + 3, 10, 0x55000000);
        roundRect(context, x0, y0, x1, y1, 10, color);
        roundRect(context, x0 + 3, y0 + 3, x1 - 3, y0 + (y1 - y0) / 2, 8, 0x1FFFFFFF);
    }

    /** Anti-alias-free rounded rectangle built from one horizontal span per row. */
    private static void roundRect(GuiGraphicsExtractor context, int x0, int y0, int x1, int y1, int radius, int color) {
        int height = y1 - y0;
        int r = Math.min(radius, Math.min(height, x1 - x0) / 2);
        for (int row = 0; row < height; row++) {
            int fromCorner = row < r ? r - row - 1 : row >= height - r ? row - (height - r) : -1;
            int inset = 0;
            if (fromCorner >= 0) {
                double dy = fromCorner + 0.5;
                inset = (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
            }
            context.fill(x0 + inset, y0 + row, x1 - inset, y0 + row + 1, color);
        }
    }

    private static void scaledText(GuiGraphicsExtractor context, Font font, String text, int centerX, int centerY, float scale, int color, boolean bold) {
        context.pose().pushMatrix();
        context.pose().translate(centerX, centerY);
        context.pose().scale(scale, scale);
        Component component = Component.literal(text);
        context.centeredText(font, bold ? component.copy().withStyle(ChatFormatting.BOLD) : component, 0, -4, color);
        context.pose().popMatrix();
    }

    private static String coins(double value) {
        if (value >= 1_000_000) return "%.1fm".formatted(value / 1_000_000);
        if (value >= 1_000) return "%.1fk".formatted(value / 1_000);
        return "%.0f".formatted(value);
    }
}
