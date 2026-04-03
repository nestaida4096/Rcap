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

public class HousingBlockScreen extends HandledScreen<HousingBlockScreenHandler> {
    private int householdSize = 1;

    public HousingBlockScreen(HousingBlockScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        super.init();
        this.householdSize = handler.getHouseholdSize();

        int x = this.width / 2 - 100;
        int y = this.height / 2 - 20;

        HouseholdSliderWidget slider = new HouseholdSliderWidget(x, y, 120, 20, handler.getHouseholdSize());

        this.addDrawableChild(slider);

        this.addDrawableChild(new ButtonWidget(x, y + 30, 60, 20, Text.literal("決定"), button -> {
            if (client != null && client.player != null && client.world != null) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(this.handler.getPos());
                buf.writeInt(slider.getValueInt()); // ← スライダーから値取得

                System.out.println("[Client] householdSize送信 = " + slider.getValueInt());
                ClientPlayNetworking.send(HousingBlockPacketReceiver.SET_HOUSEHOLD_SIZE_PACKET_ID, buf);
            }
            this.client.player.closeScreen();
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFF);
    }

    @Override
    protected void drawBackground(MatrixStack matrices, float delta, int mouseX, int mouseY) {
    }
}
