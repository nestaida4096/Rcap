package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.network.OfficeBlockPacketReceiver;
import botamochi129.botamochi.rcap.screen.OfficeBlockScreenHandler;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

public class OfficeBlockScreen extends HandledScreen<OfficeBlockScreenHandler> {
    private int officeSize = 1;

    private static final int WIDGET_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;

    public OfficeBlockScreen(OfficeBlockScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.officeSize = handler.getOfficeSize();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 15;

        // 幅を200にしてレイアウトを統一
        OfficeSliderWidget slider = new OfficeSliderWidget(centerX - WIDGET_WIDTH / 2, startY, WIDGET_WIDTH, WIDGET_HEIGHT, handler.getOfficeSize());
        this.addDrawableChild(slider);

        // 決定ボタンも幅200にして中央寄せ
        this.addDrawableChild(new ButtonWidget(centerX - WIDGET_WIDTH / 2, startY + 30, WIDGET_WIDTH, WIDGET_HEIGHT, Text.literal("決定"), button -> {
            if (client != null && client.player != null && client.world != null) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(this.handler.getPos());
                buf.writeInt(slider.getValueInt());

                System.out.println("[Client] householdSize送信 = " + slider.getValueInt());
                ClientPlayNetworking.send(OfficeBlockPacketReceiver.SET_OFFICE_STAFF_PACKET_ID, buf);
            }
            this.client.player.closeScreen();
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 15;
        drawCenteredText(matrices, this.textRenderer, this.title, centerX, startY - 20, 0xFFFFFF);
    }

    @Override
    protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
    }
}