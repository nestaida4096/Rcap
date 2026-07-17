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

    // 💡 UI配置用定数（レスポンシブ設計に基づき可変）
    private static final int MAX_LIST_WIDTH = 320;
    private static final int ENTRY_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 40; // タイトル用マージン
    private static final int FOOTER_HEIGHT = 48; // ボタン用マージン

    private final List<Long> selectedIds;

    public RouteDepotSelectScreen(Screen parentScreen, List<Long> selectedIds, boolean isRoute, Consumer<List<Long>> onConfirm) {
        super(Text.literal(isRoute ? "路線選択" : "車庫選択"));
        this.parentScreen = parentScreen;
        this.isRoute = isRoute;
        this.onConfirm = onConfirm;
        this.selectedIds = new ArrayList<>(selectedIds); // 💫 初期選択状態を保持
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // 📌 画面幅に応じてリスト幅をレスポンシブに調整（画面が狭い場合は画面端にマージンを残す）
        int listWidth = Math.min(MAX_LIST_WIDTH, this.width - 40);
        int listX = centerX - listWidth / 2;
        int listTop = HEADER_HEIGHT;
        int listBottom = this.height - FOOTER_HEIGHT;

        // リストウィジェットの生成
        listWidget = new RouteDepotListWidget(MinecraftClient.getInstance(), listWidth, this.height, listTop, listBottom, ENTRY_HEIGHT);
        listWidget.setRenderX(listX);
        setListEntries();

        // 描画・選択対象として登録
        addDrawableChild(listWidget);
        addSelectableChild(listWidget);

        // ✅ 下部ボタンの配置（レスポンシブ幅で中央揃えに2つ並べる）
        final int buttonWidth = Math.min(100, (listWidth - 8) / 2);
        final int buttonHeight = 20;
        final int buttonY = this.height - 32;

        // OK ボタン
        addDrawableChild(new ButtonWidget(
                centerX - buttonWidth - 4, buttonY, buttonWidth, buttonHeight,
                Text.literal("OK"),
                btn -> {
                    List<Long> selected = listWidget.entries.stream()
                            .filter(e -> e.selected)
                            .map(e -> e.id)
                            .toList();
                    onConfirm.accept(selected);
                    MinecraftClient.getInstance().setScreen(parentScreen);
                    System.out.println("[RCAP] OKボタン押下 - 選択されたID: " + selected);
                }));

        // キャンセル ボタン
        addDrawableChild(new ButtonWidget(
                centerX + 4, buttonY, buttonWidth, buttonHeight,
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

        // リストの描画
        listWidget.render(matrices, mouseX, mouseY, delta);

        // 画面タイトルの描画（中央上部）
        drawCenteredText(matrices, textRenderer, title, this.width / 2, 15, 0xFFFFFF);

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // 📦 路線・車庫リスト本体
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
        public int getRowWidth() {
            return this.width;
        }

        @Override
        protected int getScrollbarPositionX() {
            return this.right - 6;
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {}
    }

    // 📦 個別選択項目 (エントリ)
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
            int bgColor = 0x2A000000;
            if (selected) {
                bgColor = hovered ? 0x80336633 : 0x60224422; // 選択中は緑がかった半透明
            } else if (hovered) {
                bgColor = 0x50FFFFFF; // ホバー時は明るい半透明
            }
            fill(matrices, x, y, x + width, y + height, bgColor);

            if (selected) {
                fill(matrices, x, y, x + 2, y + height, 0xFF4CAF50); // 左端に緑のアクセントライン
            }

            MinecraftClient client = MinecraftClient.getInstance();
            TextRenderer font = client.textRenderer;

            String checkbox = selected ? "[✔]" : "[ ]";
            int checkboxColor = selected ? 0x4CAF50 : 0x888888;

            int checkboxX = x + 8;
            int textY = y + (height - 8) / 2;

            font.drawWithShadow(matrices, checkbox, checkboxX, textY, checkboxColor);

            int textX = checkboxX + font.getWidth("[✔] ") + 4;
            String displayName = font.trimToWidth(name, width - (textX - x) - 8);
            font.drawWithShadow(matrices, displayName, textX, textY, 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                this.selected = !this.selected;
                MinecraftClient.getInstance().getSoundManager().play(
                        net.minecraft.client.sound.PositionedSoundInstance.master(
                                net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F
                        )
                );
                return true;
            }
            return false;
        }
    }
}