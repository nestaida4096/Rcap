package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RcapServerPackets {

    public static final Identifier UPDATE_COMPANY = new Identifier("rcap", "update_company");
    public static final Identifier OPEN_RIDING_POS_GUI = new Identifier("rcap", "open_riding_pos_gui");
    public static final Identifier SYNC_COMPANY_LIST = new Identifier("rcap", "sync_company_list");
    public static final Identifier SYNC_PASSENGER_LIST = new Identifier("rcap", "sync_passenger_list");

    public static void sendPassengerList(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        List<Passenger> list = PassengerManager.PASSENGER_LIST;
        buf.writeInt(list.size());
        for (Passenger p : list) buf.writeNbt(p.toNbt());
        ServerPlayNetworking.send(player, SYNC_PASSENGER_LIST, buf);
    }

    public static void sendCompanyList(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        List<Company> companies = CompanyManager.COMPANY_LIST;
        buf.writeInt(companies.size());
        for (Company c : companies) {
            buf.writeNbt(c.toNBT()); // Company に NbtCompound 変換用メソッドが必要
        }

        ServerPlayNetworking.send(player, SYNC_COMPANY_LIST, buf);
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_COMPANY, (server, player, handler, buf, sender) -> {
            long id = buf.readLong();
            String name = buf.readString();
            int color = buf.readInt();

            int routeSize = buf.readInt();
            List<Long> routeIds = new ArrayList<>();
            for (int i = 0; i < routeSize; i++) routeIds.add(buf.readLong());

            int depotSize = buf.readInt();
            List<Long> depotIds = new ArrayList<>();
            for (int i = 0; i < depotSize; i++) depotIds.add(buf.readLong());
        });
    }

    public static void sendOpenGui(ServerPlayerEntity player, BlockPos pos, long platformId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeLong(platformId);
        ServerPlayNetworking.send(player, OPEN_RIDING_POS_GUI, buf);
    }
}
