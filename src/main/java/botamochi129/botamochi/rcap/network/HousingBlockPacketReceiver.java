package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;

public class HousingBlockPacketReceiver {
    public static final Identifier SET_HOUSEHOLD_SIZE_PACKET_ID = new Identifier("rcap", "set_household_size");

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(SET_HOUSEHOLD_SIZE_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int newSize = buf.readInt();

            System.out.println("[Server] パケット受信！ pos=" + pos + ", size=" + newSize); // ← 追加！

            server.execute(() -> {
                if (player.getWorld().getBlockEntity(pos) instanceof HousingBlockEntity entity) {
                    entity.setHouseholdSize(newSize);
                    System.out.println("[Server] HouseholdSize を " + newSize + " に設定しました"); // ← 追加！
                } else {
                    System.out.println("[Server] BlockEntity が HousingBlockEntity ではありません！");
                }
            });
        });
    }
}
