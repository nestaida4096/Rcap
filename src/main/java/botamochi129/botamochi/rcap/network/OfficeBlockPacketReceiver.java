package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.block.entity.OfficeBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class OfficeBlockPacketReceiver {
    public static final Identifier SET_OFFICE_STAFF_PACKET_ID = new Identifier("rcap", "set_office_staff");

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(SET_OFFICE_STAFF_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int newStaffCount = buf.readInt();

            System.out.println("[Server] Officeパケット受信！ pos=" + pos + ", staff=" + newStaffCount);

            server.execute(() -> {
                if (player.getWorld().getBlockEntity(pos) instanceof OfficeBlockEntity entity) {
                    entity.setStaffCount(newStaffCount);
                    System.out.println("[Server] OfficeBlockEntity に staffCount = " + newStaffCount + " を保存しました。");
                } else {
                    System.out.println("[Server] 指定位置に OfficeBlockEntity が見つかりません！");
                }
            });
        });
    }
}
