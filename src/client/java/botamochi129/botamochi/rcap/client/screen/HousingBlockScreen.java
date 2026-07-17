package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.network.HousingBlockPacketReceiver;
import botamochi129.botamochi.rcap.screen.HousingBlockScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class HousingBlockScreen extends HandledScreen<HousingBlockScreenHandler> {
    private record OfficeChoice(long posLong, String label) {}

    private int householdSize = 1;
    private final List<OfficeChoice> officeChoices = new ArrayList<>();
    private int selectedOfficeIndex = 0;

    private static final int WIDGET_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;

    public HousingBlockScreen(HousingBlockScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.householdSize = handler.getHouseholdSize();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 35; // 縦方向の配置を中央にバランスよく分散

        // スライダー (幅を200にして統一感を持たせる)
        HouseholdSliderWidget slider = new HouseholdSliderWidget(centerX - WIDGET_WIDTH / 2, startY, WIDGET_WIDTH, WIDGET_HEIGHT, handler.getHouseholdSize());

        officeChoices.clear();
        officeChoices.add(new OfficeChoice(HousingBlockPacketReceiver.RANDOM_OFFICE_SENTINEL, "ランダム"));
        for (HousingBlockScreenHandler.OfficeOption option : handler.getOfficeOptions()) {
            officeChoices.add(new OfficeChoice(option.posLong(), option.label()));
        }
        selectedOfficeIndex = getInitialOfficeIndex(handler.getLinkedOfficePosLong());

        this.addDrawableChild(slider);

        // 勤務先ボタン (幅200)
        ButtonWidget officeButton = new ButtonWidget(centerX - WIDGET_WIDTH / 2, startY + 30, WIDGET_WIDTH, WIDGET_HEIGHT, Text.literal(getSelectedOfficeLabel()), button -> {
            if (!officeChoices.isEmpty()) {
                selectedOfficeIndex = (selectedOfficeIndex + 1) % officeChoices.size();
                button.setMessage(Text.literal(getSelectedOfficeLabel()));
            }
        });
        this.addDrawableChild(officeButton);

        // 決定ボタン (幅を200に統一してセンタリング)
        this.addDrawableChild(new ButtonWidget(centerX - WIDGET_WIDTH / 2, startY + 60, WIDGET_WIDTH, WIDGET_HEIGHT, Text.literal("決定"), button -> {
            if (client != null && client.player != null && client.world != null) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(this.handler.getPos());
                buf.writeInt(slider.getValueInt());
                buf.writeLong(getSelectedOfficePosLong());

                System.out.println("[Client] householdSize送信 = " + slider.getValueInt() + ", office=" + getSelectedOfficePosLong());
                ClientPlayNetworking.send(HousingBlockPacketReceiver.SET_HOUSEHOLD_SIZE_PACKET_ID, buf);
            }
            this.client.player.closeScreen();
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);

        // タイトルとラベルの位置をコントロール
        int centerX = this.width / 2;
        int startY = this.height / 2 - 35;

        drawCenteredText(matrices, this.textRenderer, this.title, centerX, startY - 20, 0xFFFFFF);
        drawTextWithShadow(matrices, this.textRenderer, Text.literal("勤務先設定"), centerX - WIDGET_WIDTH / 2, startY + 18, 0xAAAAAA);
    }

    @Override
    protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
    }

    private int getInitialOfficeIndex(long linkedOfficePosLong) {
        for (int i = 0; i < officeChoices.size(); i++) {
            if (officeChoices.get(i).posLong() == linkedOfficePosLong) {
                return i;
            }
        }
        return 0;
    }

    private String getSelectedOfficeLabel() {
        return officeChoices.isEmpty() ? "ランダム" : officeChoices.get(selectedOfficeIndex).label();
    }

    private long getSelectedOfficePosLong() {
        return officeChoices.isEmpty() ? HousingBlockPacketReceiver.RANDOM_OFFICE_SENTINEL : officeChoices.get(selectedOfficeIndex).posLong();
    }
}