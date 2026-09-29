package net.plotshop.manager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * Visual settings screen for the trade assistant. Each row is a toggle button
 * so the player can flip the trade-monitor options without typing commands.
 */
public final class PlotShopSettingsScreen extends Screen {

    private final TradeMonitor monitor;
    private final Screen parent;

    public PlotShopSettingsScreen(TradeMonitor monitor, Screen parent) {
        super(Text.literal("PlotShop 设置"));
        this.monitor = monitor;
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    /** Rebuilds all buttons to reflect the current toggle state. */
    private void rebuild() {
        clearChildren();
        int width = 200;
        int x = this.width / 2 - width / 2;
        int y = this.height / 2 - 60;

        addDrawableChild(toggle(x, y, "误交易检测", monitor.mistradeEnabled(), monitor::setMistrade));
        y += 26;
        addDrawableChild(toggle(x, y, "公平价格估算", monitor.fairPriceEnabled(), monitor::setFairPrice));
        y += 26;
        addDrawableChild(toggle(x, y, "行为日志", monitor.logEnabled(), monitor::setLog));
        y += 26;
        addDrawableChild(toggle(x, y, "交易 HUD", monitor.hudEnabled(), monitor::setHud));

        y += 32;
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(x, y, width, 20)
                .build());
    }

    private ButtonWidget toggle(int x, int y, String label, boolean on, Consumer<Boolean> setter) {
        return ButtonWidget.builder(Text.literal((on ? "§a[开] " : "§c[关] ") + label), b -> {
                    setter.accept(!on);
                    rebuild();
                })
                .dimensions(x, y, 200, 20)
                .build();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("§7点击条目切换开关"), this.width / 2, 32, 0xAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
