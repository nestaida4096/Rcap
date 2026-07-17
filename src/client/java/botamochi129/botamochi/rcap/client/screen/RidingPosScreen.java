package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;
import botamochi129.botamochi.rcap.client.network.ClientNetworking;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.RailwayData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class RidingPosScreen extends Screen {

    private static final int MAX_LIST_WIDTH = 300;
    private static final int ITEM_HEIGHT = 20;

    private final RidingPosBlockEntity blockEntity;
    private final BlockPos blockPos;

    private ScrollablePlatformList platformList;
    private ButtonWidget saveButton;

    private final Set<Long> selectedPlatformIds = new HashSet<>();
    private List<Platform> nearbyPlatforms = new ArrayList<>();

    private int listWidth;

    public RidingPosScreen(RidingPosBlockEntity blockEntity) {
        super(Text.literal("乗車位置設定"));
        this.blockEntity = blockEntity;
        this.blockPos = blockEntity.getPos();
    }

    @Override
    protected void init() {
        selectedPlatformIds.clear();
        if (blockEntity.getPlatformId() != -1) {
            selectedPlatformIds.add(blockEntity.getPlatformId());
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        // 📌 リストの横幅を画面サイズに応じてレスポンシブ化
        this.listWidth = Math.min(MAX_LIST_WIDTH, this.width - 40);
        int listX = (this.width - this.listWidth) / 2;
        int listY = 40;
        int listHeight = this.height - 90; // 下部ボタンの高さ(40)に配慮して動的に計算

        platformList = new ScrollablePlatformList(mc, this.listWidth, listHeight, listY, listY + listHeight, ITEM_HEIGHT);
        platformList.setRenderBackground(false);
        platformList.setLeftPos(listX);
        addSelectableChild(platformList);

        var world = mc.world;
        if (world == null) {
            addSaveButton();
            return;
        }

        var station = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, blockPos);
        if (station == null) {
            addSaveButton();
            return;
        }

        nearbyPlatforms = new ArrayList<>(ClientData.DATA_CACHE.requestStationIdToPlatforms(station.id).values());
        nearbyPlatforms.sort(Comparator.comparing(p -> p.name));

        for (Platform platform : nearbyPlatforms) {
            boolean checked = selectedPlatformIds.contains(platform.id);
            // チェックボックス幅もリスト幅に合わせて動的にアジャスト
            platformList.addPublicEntry(new PlatformEntry(platform.name + "（ID: " + platform.id + "）", platform.id, checked, this.listWidth));
        }

        addSaveButton();
    }

    private void addSaveButton() {
        int buttonWidth = 100;
        int bx = (this.width - buttonWidth) / 2;
        int by = this.height - 35;
        saveButton = new ButtonWidget(bx, by, buttonWidth, 20, Text.literal("保存"), b -> closeWithSave());
        addDrawableChild(saveButton);
    }

    private void closeWithSave() {
        long selectedId = -1L;
        if (platformList != null) {
            for (PlatformEntry entry : platformList.children()) {
                if (entry.checkbox.isChecked()) {
                    selectedId = entry.platformId;
                    break;
                }
            }
        }

        ClientNetworking.sendUpdatePlatformIdPacket(blockPos, selectedId);
        MinecraftClient.getInstance().setScreen(null);
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public void updateSelectedPlatform(long platformId) {
        if (platformList == null) {
            return;
        }
        platformList.children().forEach(entry -> {
            entry.checkbox.setChecked(entry.platformId == platformId);
        });
    }

    @Override
    public void close() {
        closeWithSave();
        super.close();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, title.getString(), width / 2, 15, 0xFFFFFF);

        if (platformList != null) {
            platformList.render(matrices, mouseX, mouseY, delta);
        }

        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------- スクロール対応リスト ----------
    private class ScrollablePlatformList extends EntryListWidget<PlatformEntry> {
        public ScrollablePlatformList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
        }

        public void addPublicEntry(PlatformEntry entry) {
            super.addEntry(entry);
        }

        @Override
        protected int getScrollbarPositionX() {
            return getRowLeft() + this.width - 8;
        }

        @Override
        public int getRowWidth() {
            return this.width;
        }

        @Override
        public void appendNarrations(NarrationMessageBuilder builder) {
        }
    }

    public static class PlatformEntry extends EntryListWidget.Entry<PlatformEntry> {
        public final long platformId;
        public final MyCheckboxWidget checkbox;
        private final int listWidth;

        public PlatformEntry(String label, long platformId, boolean selected, int listWidth) {
            this.platformId = platformId;
            this.listWidth = listWidth;
            this.checkbox = new MyCheckboxWidget(0, 0, listWidth - 10, ITEM_HEIGHT, Text.literal(label), selected);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return checkbox.mouseClicked(mouseX, mouseY, button);
        }

        public List<MyCheckboxWidget> children() {
            return List.of(checkbox);
        }

        public List<MyCheckboxWidget> selectableChildren() {
            return List.of(checkbox);
        }

        @Override
        public void render(MatrixStack matrices, int index, int y, int x, int entryWidth,
                           int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            checkbox.x = x + 5;
            checkbox.y = y;
            checkbox.render(matrices, mouseX, mouseY, delta);
        }
    }

    public static class MyCheckboxWidget extends CheckboxWidget {

        public MyCheckboxWidget(int x, int y, int width, int height, Text message, boolean checked) {
            super(x, y, width, height, message, checked);
        }

        public void setChecked(boolean value) {
            if (value != isChecked()) {
                this.onPress();
            }
        }

        public boolean isChecked() {
            return super.isChecked();
        }
    }
}