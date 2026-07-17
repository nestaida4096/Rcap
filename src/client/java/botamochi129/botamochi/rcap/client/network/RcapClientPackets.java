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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RcapClientPackets {

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RcapServerPackets.SYNC_COMPANY_LIST, (client, handler, buf, responseSender) -> {
            int size = buf.readInt();
            List<Company> companies = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                companies.add(Company.fromNBT(buf.readNbt()));
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
                    client.setScreen(new RidingPosScreen(ridingPos));
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(RcapServerPackets.SYNC_PASSENGER_LIST, (client, handler, buf, sender) -> {
            int size = buf.readInt();
            ArrayList<Passenger> incomingList = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                incomingList.add(Passenger.fromNbt(buf.readNbt()));
            }

            client.execute(() -> {
                Map<Long, Passenger> existingMap = new HashMap<>();
                for (Passenger p : PassengerManager.PASSENGER_LIST) {
                    if (p != null) {
                        existingMap.put(p.id, p);
                    }
                }

                ArrayList<Passenger> mergedList = new ArrayList<>();
                for (Passenger incoming : incomingList) {
                    Passenger existing = existingMap.get(incoming.id);
                    if (existing != null) {
                        incoming.lastTickX = existing.lastTickX;
                        incoming.lastTickZ = existing.lastTickZ;
                        incoming.stuckTicks = existing.stuckTicks;

                        // 乗車中の列車の ID・号車番号のみを引き継ぐ（号車ワープ防止）
                        if (incoming.moveState == Passenger.MoveState.ON_TRAIN) {
                            incoming.currentTrainId = existing.currentTrainId;
                            incoming.currentCarIndex = existing.currentCarIndex;
                        }

                        // ★改善:
                        // 前回はここで `incoming.x = existing.x;` のように座標を上書きしていたため、
                        // サーバーの進行座標が無視されて動かなくなっていました。
                        // 今回はサーバーからの最新座標（x, y, z）をそのまま正とし、描画側で補間をさせます。

                    } else {
                        incoming.lastTickX = incoming.x;
                        incoming.lastTickZ = incoming.z;
                    }
                    mergedList.add(incoming);
                }

                PassengerManager.PASSENGER_LIST.clear();
                PassengerManager.PASSENGER_LIST.addAll(mergedList);
            });
        });
    }
}