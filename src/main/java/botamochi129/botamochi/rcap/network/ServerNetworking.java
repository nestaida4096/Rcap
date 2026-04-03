package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ServerNetworking {
    public static final Identifier UPDATE_COMPANY = new Identifier("rcap", "update_company");
    public static final Identifier DELETE_COMPANY = new Identifier("rcap", "delete_company");
    public static final Identifier CREATE_COMPANY = new Identifier("rcap", "create_company");

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_COMPANY, (server, player, handler, buf, responseSender) -> {
            long id = buf.readLong();
            String name = buf.readString();
            int color = buf.readInt();
            server.execute(() -> {
                Company company = CompanyManager.getById(id);
                if (company != null) {
                    company.name = name;
                    company.color = color;

                    CompanyManager.save();
                    CompanyManager.broadcastToAllPlayers(server);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(CREATE_COMPANY, (server, player, handler, buf, responseSender) -> {
            long id = buf.readLong();
            String name = buf.readString();
            int color = buf.readInt();

            server.execute(() -> {
                if (CompanyManager.getById(id) == null) {
                    CompanyManager.COMPANY_LIST.add(new Company(id, name, color));
                    CompanyManager.save(); // 忘れずに保存しておく

                    // 🔁 全クライアントに同期！
                    CompanyManager.broadcastToAllPlayers(server);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(DELETE_COMPANY, (server, player, handler, buf, responseSender) -> {
            long id = buf.readLong();

            server.execute(() -> {
                Company company = CompanyManager.getById(id);
                if (company != null) {
                    CompanyManager.COMPANY_LIST.remove(company);
                    CompanyManager.save();

                    // 🔁 削除後にも全プレイヤーへ再送
                    CompanyManager.broadcastToAllPlayers(server);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(new Identifier("rcap", "update_platform_id"),
                (server, player, handler, buf, responseSender) -> {
                    BlockPos pos = buf.readBlockPos();
                    long platformId = buf.readLong();

                    server.execute(() -> {
                        if (player.getWorld().getBlockEntity(pos) instanceof RidingPosBlockEntity entity) {
                            entity.setPlatformId(platformId);
                        }
                    });
                }
        );
    }
}
