package botamochi129.botamochi.rcap.client.screen;

import mtr.client.ClientData;
import mtr.data.Depot;
import mtr.data.Route;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RouteDepotSelectScreen extends Screen {

    private final Screen parentScreen;
    private final boolean isRoute;
    private final Consumer<List<Long>> onConfirm;

    private RouteDepotListWidget listWidget;

    // 💡 定数
    private static final int LIST_WIDTH = 300;
    private static final int LIST_HEIGHT = 120;
    private static final int ENTRY_HEIGHT = 24;
    private static final int MARGIN_Y = 60;

    private List<Long> selectedIds;

    public RouteDepotSelectScreen(Screen parentScreen, List<Long> selectedIds, boolean isRoute, Consumer<List<Long>> onConfirm) {
        super(Text.literal(isRoute ? "路線選択" : "車庫選択"));
        this.parentScreen = parentScreen;
        this.isRoute = isRoute;
        this.onConfirm = onConfirm;
        this.selectedIds = new ArrayList<>(selectedIds); // ← 💫 deep copy で保持！
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // 📌 中央配置
        int listX = centerX - LIST_WIDTH / 2;
        int listBottom = this.height - MARGIN_Y;
        int listTop = listBottom - LIST_HEIGHT;

        listWidget = new RouteDepotListWidget(MinecraftClient.getInstance(), LIST_WIDTH, this.height, listTop, listBottom, ENTRY_HEIGHT);
        listWidget.setRenderX(listX);
        setListEntries();

        addDrawableChild(listWidget);
        addSelectableChild(listWidget);

        // ✅ ボタン配置（画面下中央に配置）
        final int buttonWidth = 98;
        final int buttonHeight = 20;
        final int buttonY = this.height - buttonHeight - 20;

        // OK
        addDrawableChild(new ButtonWidget(
                centerX - buttonWidth - 2, buttonY, buttonWidth, buttonHeight,
                Text.literal("OK"),
                btn -> {
                    List<Long> selected = listWidget.entries.stream().filter(e -> e.selected).map(e -> e.id).toList();
                    onConfirm.accept(selected);
                    MinecraftClient.getInstance().setScreen(parentScreen);// 🔍 ログ追加：
                    System.out.println("[RCAP] OKボタン押下 - 選択されたID: " + selected);
                }));

        // キャンセル
        addDrawableChild(new ButtonWidget(
                centerX + 2, buttonY, buttonWidth, buttonHeight,
                Text.literal("キャンセル"),
                btn -> MinecraftClient.getInstance().setScreen(parentScreen)));
    }

    private void setListEntries() {
        if (isRoute) {
            for (Route route : ClientData.ROUTES) {
                boolean selected = selectedIds.contains(route.id);
                listWidget.addEntry(new SelectableEntry(route.id, route.name, selected));
            }
        } else {
            for (Depot depot : ClientData.DEPOTS) {
                boolean selected = selectedIds.contains(depot.id);
                listWidget.addEntry(new SelectableEntry(depot.id, depot.name, selected));
            }
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // ✅ タイトル中央表示
        drawCenteredText(matrices, textRenderer, title, width / 2, 15, 0xFFFFFF);

        listWidget.render(matrices, mouseX, mouseY, delta);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // 📦 リスト本体
    static class RouteDepotListWidget extends EntryListWidget<SelectableEntry> {
        public final List<SelectableEntry> entries = new ArrayList<>();

        public RouteDepotListWidget(MinecraftClient client, int width, int height, int top, int bottom, int entryHeight) {
            super(client, width, height, top, bottom, entryHeight);
        }

        public void setRenderX(int x) {
            this.left = x;
            this.right = x + this.width;
        }

        @Override
        public int addEntry(SelectableEntry entry) {
            entries.add(entry);
            return super.addEntry(entry);
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {}
    }

    // 📦 個別選択項目
    static class SelectableEntry extends EntryListWidget.Entry<SelectableEntry> {
        public final long id;
        public final String name;
        public boolean selected;

        public SelectableEntry(long id, String name, boolean selected) {
            this.id = id;
            this.name = name;
            this.selected = selected;
        }

        @Override
        public void render(MatrixStack matrices, int index, int y, int x, int width, int height,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            int bgColor = hovered ? 0xFF555555 : 0xFF202020;
            fill(matrices, x, y, x + width, y + height, bgColor);

            MinecraftClient client = MinecraftClient.getInstance();
            TextRenderer font = client.textRenderer;

            String checkbox = selected ? "✔" : "□";
            String text = checkbox + " " + name;

            int textWidth = font.getWidth(text);
            int centerX = x + (width - textWidth) / 2;

            font.drawWithShadow(matrices, text, centerX, y + 6, 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            selected = !selected;
            return true;
        }
    }
}
