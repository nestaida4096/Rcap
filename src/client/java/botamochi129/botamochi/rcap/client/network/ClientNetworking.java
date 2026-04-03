package botamochi129.botamochi.rcap.client.network;

import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.network.RcapServerPackets;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ClientNetworking {
    public static final Identifier UPDATE_PLATFORM_ID = new Identifier("rcap", "update_platform_id");

    public static void sendUpdatePlatformIdPacket(BlockPos pos, long platformId) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeBlockPos(pos);
        buf.writeLong(platformId);
        ClientPlayNetworking.send(UPDATE_PLATFORM_ID, buf);
    }

    public static void sendUpdateCompanyPacket(Company company) {
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeLong(company.id);
        buf.writeString(company.name);
        buf.writeInt(company.color);

        buf.writeInt(company.ownedRoutes.size());
        for (Long id : company.ownedRoutes) buf.writeLong(id);

        buf.writeInt(company.ownedDepots.size());
        for (Long id : company.ownedDepots) buf.writeLong(id);

        ClientPlayNetworking.send(RcapServerPackets.UPDATE_COMPANY, buf);
    }

    public static void sendCreateCompanyPacket(Company company) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(company.id);
        buf.writeString(company.name);
        buf.writeInt(company.color);
        ClientPlayNetworking.send(new Identifier("rcap", "create_company"), buf);
    }

    public static void sendDeleteCompanyPacket(long companyId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeLong(companyId);
        ClientPlayNetworking.send(new Identifier("rcap", "delete_company"), buf);
    }
}
