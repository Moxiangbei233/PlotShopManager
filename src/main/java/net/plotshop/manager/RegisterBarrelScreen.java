package net.plotshop.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Visual barrel registration: type the item name, its sign alias, the buy/sell
 * prices and an optional barrel number, see a live sign preview on the right,
 * then confirm to register the barrel and copy the sign text to the clipboard.
 *
 * <p>The last confirmed input is persisted and restored as the default on the
 * next open, so a shop can quickly create many barrels with the same name and
 * different prices.</p>
 */
public class RegisterBarrelScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BarrelStore barrelStore;
    private final String world;
    private final BlockPos pos;

    private TextFieldWidget itemField;
    private TextFieldWidget aliasField;
    private TextFieldWidget buyField;
    private TextFieldWidget sellField;
    private TextFieldWidget tierField;
    private ButtonWidget typeButton;

    private String type = "exclusive";
    private String error = "";

    private int leftX;
    private int topY;

    /** Sign lines waiting to be auto-filled into the next sign edit screen. */
    private static String[] pendingSignLines;

    public RegisterBarrelScreen(BarrelStore barrelStore, String world, BlockPos pos) {
        super(Text.literal("注册桶商店"));
        this.barrelStore = barrelStore;
        this.world = world;
        this.pos = pos;
    }

    @Override
    protected void init() {
        leftX = (width - 400) / 2;
        topY = 40;

        int fx = leftX;
        int fw = 160;

        LastInput last = loadLast();

        itemField = field(fx, topY, fw, "物品名称，如 Soul Essence");
        itemField.setText(last.item);
        aliasField = field(fx, topY + 28, fw, "告示牌别名/简称，如 white mat");
        aliasField.setText(last.alias);
        buyField = field(fx, topY + 56, fw, "如 4xp");
        buyField.setText(last.buy);
        sellField = field(fx, topY + 84, fw, "如 6xp");
        sellField.setText(last.sell);
        tierField = field(fx, topY + 112, 70, "1-5");
        tierField.setMaxLength(2);
        tierField.setText(last.tier > 0 ? String.valueOf(last.tier) : "");

        type = last.type == null || last.type.isEmpty() ? "exclusive" : last.type;
        typeButton = ButtonWidget.builder(Text.literal("类型：" + typeName()), b -> cycleType())
                .dimensions(fx + 78, topY + 112, 82, 20).build();
        addDrawableChild(typeButton);

        ButtonWidget confirm = ButtonWidget.builder(Text.literal("确定并复制告示牌"), b -> confirm())
                .dimensions(fx, topY + 142, fw, 20).build();
        addDrawableChild(confirm);

        ButtonWidget cancel = ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
                .dimensions(fx, topY + 168, fw, 20).build();
        addDrawableChild(cancel);

        updateFieldsForType();
    }

    private TextFieldWidget field(int x, int y, int w, String placeholder) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(""));
        f.setMaxLength(64);
        f.setPlaceholder(Text.literal(placeholder));
        addDrawableChild(f);
        return f;
    }

    private void cycleType() {
        switch (type) {
            case "exclusive": type = "rare"; break;
            case "rare": type = "custom"; break;
            default: type = "exclusive";
        }
        error = "";
        updateFieldsForType();
    }

    private void updateFieldsForType() {
        boolean custom = "custom".equals(type);
        sellField.visible = !custom;
        typeButton.setMessage(Text.literal("类型：" + typeName()));
    }

    private String typeName() {
        switch (type) {
            case "rare": return "rare通用";
            case "custom": return "互换";
            default: return "专属";
        }
    }

    private void confirm() {
        String item = itemField.getText().trim();
        String alias = aliasField.getText().trim();
        String buy = buyField.getText().trim();
        String sell = sellField.getText().trim();
        int tier = parseTier(tierField.getText());

        if (item.isEmpty()) {
            error = "请填写物品名称";
            return;
        }
        if (!"custom".equals(type) && buy.isEmpty() && sell.isEmpty()) {
            error = "请填写买入价或卖出价";
            return;
        }

        String resolved = AliasStore.get().resolve(item);
        Barrel barrel = new Barrel(world, pos.getX(), pos.getY(), pos.getZ(), resolved, "", "");
        barrel.type = type;
        barrel.tier = tier;
        if ("custom".equals(type)) {
            barrel.exchangeFee = buy; // the buy field doubles as the exchange fee
        }
        else {
            barrel.buyPrice = buy;
            barrel.sellPrice = sell;
        }

        // Record the alias: prefer the alias field, fall back to the typed item
        // when it turned out to be a known alias itself.
        if (!alias.isEmpty() && !alias.equalsIgnoreCase(resolved)) {
            barrel.alias = alias;
            AliasStore.get().put(alias, resolved);
        }
        else if (!resolved.equals(item)) {
            barrel.alias = item;
        }

        barrelStore.upsert(barrel);
        barrelStore.save();

        String signName = alias.isEmpty() ? resolved : alias;
        String[] signLines = signLines(signName, buy, sell, type, tier);
        String signText = String.join("\n", signLines);
        pendingSignLines = signLines;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.keyboard != null) {
            client.keyboard.setClipboard(signText);
        }

        if (client.player != null) {
            client.player.sendMessage(Text.literal(PlotShopManagerClient.PREFIX
                    + "§a已注册桶 §b@" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " §f[§d" + barrel.typeLabel() + "§f] §a" + barrel.item
                    + (barrel.alias.isEmpty() ? "" : " §7(" + barrel.alias + ")")
                    + " §f，请放置/编辑告示牌，文字将自动填入"), false);
        }

        saveLast(item, alias, buy, sell, tier, type);
        close();
    }

    /** Take the pending sign lines once; returns null when nothing is waiting. */
    public static String[] consumePendingSignLines() {
        String[] lines = pendingSignLines;
        pendingSignLines = null;
        return lines;
    }

    // ---- last-input persistence -------------------------------------------

    /** The last confirmed registration input, restored as the default next time. */
    private static final class LastInput {
        String item = "";
        String alias = "";
        String buy = "";
        String sell = "";
        int tier;
        String type = "exclusive";
    }

    private static Path lastFile() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("plotshop-manager").resolve("register_last.json");
    }

    private static LastInput loadLast() {
        Path file = lastFile();
        if (!Files.exists(file)) {
            return new LastInput();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            LastInput last = GSON.fromJson(reader, LastInput.class);
            return last == null ? new LastInput() : last;
        }
        catch (IOException ignored) {
            return new LastInput();
        }
    }

    private static void saveLast(String item, String alias, String buy, String sell,
                                 int tier, String type) {
        LastInput last = new LastInput();
        last.item = item;
        last.alias = alias;
        last.buy = buy;
        last.sell = sell;
        last.tier = tier;
        last.type = type;
        try {
            Files.createDirectories(lastFile().getParent());
            try (Writer writer = Files.newBufferedWriter(lastFile(), StandardCharsets.UTF_8)) {
                GSON.toJson(last, writer);
            }
        }
        catch (IOException ignored) {
            // Best effort; defaults simply won't persist for the next session.
        }
    }

    private static int parseTier(String text) {
        try {
            int tier = Integer.parseInt(text.trim());
            return tier > 0 && tier < 100 ? tier : 0;
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The name that appears on the sign: the alias, or the resolved item name. */
    private String previewName() {
        String alias = aliasField.getText().trim();
        if (!alias.isEmpty()) {
            return alias;
        }
        String item = itemField.getText().trim();
        if (item.isEmpty()) {
            return "示例物品";
        }
        return AliasStore.get().resolve(item);
    }

    private String[] previewLines() {
        String buy = buyField.getText().trim();
        String sell = sellField.getText().trim();
        return signLines(previewName(), buy, sell, type, parseTier(tierField.getText()));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        boolean custom = "custom".equals(type);
        drawLabel(context, itemField, "物品名称");
        drawLabel(context, aliasField, "别名 (可选)");
        drawLabel(context, buyField, custom ? "手续费 (0.5har / free)" : "买入价");
        if (!custom) {
            drawLabel(context, sellField, "卖出价");
        }
        drawLabel(context, tierField, "桶号 (1-5)");

        if (!error.isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("§c" + error),
                    leftX, topY + 194, 0xFFFFFF);
        }

        renderSignPreview(context, previewLines());
    }

    private void drawLabel(DrawContext context, TextFieldWidget field, String text) {
        context.drawTextWithShadow(textRenderer, Text.literal(text),
                field.getX(), field.getY() - 12, 0xCCCCCC);
    }

    private void renderSignPreview(DrawContext context, String[] lines) {
        int x = leftX + 190;
        int y = topY;
        int w = 200;
        int h = 82;

        context.drawTextWithShadow(textRenderer, Text.literal("§e告示牌预览"), x, y - 14, 0xFFFFFF);

        // Wooden sign panel.
        context.fill(x, y, x + w, y + h, 0xFF6B4423);
        context.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF8B5A2B);

        for (int i = 0; i < 4; i++) {
            String line = lines[i] == null ? "" : lines[i];
            int color = line.length() > 15 ? 0xFFFF5555 : 0xFF2A1608;
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(line),
                    x + w / 2, y + 8 + i * 17, color);
        }

        context.drawTextWithShadow(textRenderer,
                Text.literal("§7每行最多 15 字符，超长显示红色"), x, y + h + 4, 0xFFFFFF);
    }

    /** Generate the four sign lines, each at most 15 characters. */
    private static String[] signLines(String name, String buy, String sell, String type, int tier) {
        String[] lines = new String[4];
        String tierSuffix = tier > 0 ? "#" + tier : "";
        if (name.length() <= 15) {
            lines[0] = name;
            lines[1] = tierSuffix.isEmpty() ? "======" : tierSuffix + " ======";
        }
        else {
            lines[0] = name.substring(0, 15);
            String rest = name.substring(15);
            int maxRest = tierSuffix.isEmpty() ? 15 : 15 - tierSuffix.length() - 1;
            if (rest.length() > maxRest) {
                rest = rest.substring(0, maxRest);
            }
            lines[1] = tierSuffix.isEmpty() ? rest : rest + " " + tierSuffix;
        }
        if ("custom".equals(type)) {
            lines[2] = "exch for " + (buy.isEmpty() ? "free" : buy);
            lines[3] = "";
        }
        else {
            lines[2] = buy.isEmpty() ? "" : "buy for " + buy;
            lines[3] = sell.isEmpty() ? "" : "sell for " + sell;
        }
        return lines;
    }
}
