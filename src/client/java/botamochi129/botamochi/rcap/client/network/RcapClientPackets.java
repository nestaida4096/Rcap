package botamochi129.botamochi.rcap.client.network;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;
import botamochi129.botamochi.rcap.client.screen.RidingPosScreen;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import botamochi129.botamochi.rcap.network.RcapServerPackets;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RcapClientPackets {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RcapServerPackets.SYNC_COMPANY_LIST, (client, handler, buf, responseSender) -> {
            int size = buf.readInt();
            List<Company> companies = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                companies.add(Company.fromNBT(buf.readNbt())); // ← Company.fromNBT は既にあり ✅
            }

            client.execute(() -> {
                CompanyManager.COMPANY_LIST.clear();
                CompanyManager.COMPANY_LIST.addAll(companies);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RcapServerPackets.OPEN_RIDING_POS_GUI, (client, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            long platformId = buf.readLong();

            client.execute(() -> {
                var world = MinecraftClient.getInstance().world;
                if (world == null) return;

                var be = world.getBlockEntity(pos);
                if (be instanceof RidingPosBlockEntity ridingPos) {
                    ridingPos.setPlatformId(platformId);

                    // ✅ GUIを開く
                    client.setScreen(new RidingPosScreen(ridingPos));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RcapServerPackets.SYNC_PASSENGER_LIST, (client, handler, buf, sender) -> {
            int size = buf.readInt();
            ArrayList<Passenger> list = new ArrayList<>();
            for (int i = 0; i < size; i++) list.add(Passenger.fromNbt(buf.readNbt()));
            client.execute(() -> {
                PassengerManager.PASSENGER_LIST.clear();
                PassengerManager.PASSENGER_LIST.addAll(list);
            });
        });
    }
}
