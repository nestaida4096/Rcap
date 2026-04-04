package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;

public class HousingBlockPacketReceiver {
    public static final Identifier SET_HOUSEHOLD_SIZE_PACKET_ID = new Identifier("rcap", "set_household_size");
    public static final long RANDOM_OFFICE_SENTINEL = Long.MIN_VALUE;

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(SET_HOUSEHOLD_SIZE_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int newSize = buf.readInt();
            long officePosLong = buf.readLong();

            System.out.println("[Server] パケット受信！ pos=" + pos + ", size=" + newSize + ", office=" + officePosLong);

            server.execute(() -> {
                if (player.getWorld().getBlockEntity(pos) instanceof HousingBlockEntity entity) {
                    entity.setHouseholdSize(newSize);
                    entity.setLinkedOfficePosLong(officePosLong == RANDOM_OFFICE_SENTINEL ? null : officePosLong);
                    System.out.println("[Server] HouseholdSize を " + newSize + " に設定しました");
                } else {
                    System.out.println("[Server] BlockEntity が HousingBlockEntity ではありません！");
                }
            });
        });
    }
}
