package botamochi129.botamochi.rcap.network;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RcapServerPackets {

    public static final Identifier UPDATE_COMPANY = new Identifier("rcap", "update_company");
    public static final Identifier OPEN_RIDING_POS_GUI = new Identifier("rcap", "open_riding_pos_gui");
    public static final Identifier SYNC_COMPANY_LIST = new Identifier("rcap", "sync_company_list");
    public static final Identifier SYNC_PASSENGER_LIST = new Identifier("rcap", "sync_passenger_list");

    // パケット書き出し専用のスレッドプール（メインスレッドでのNBT変換負荷を完全排除）
    private static final ExecutorService PACKET_SERIALIZE_EXECUTOR = Executors.newSingleThreadExecutor();

    // 各プレイヤーに最後に送信した乗客の座標キャッシュ（差分同期用）
    private static final Map<String, Map<Long, LastSentState>> lastSentPositions = new ConcurrentHashMap<>();

    private static class LastSentState {
        final double x, y, z;
        final int moveStateOrdinal;
        final int routeTargetIndex;

        LastSentState(Passenger p) {
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
            this.moveStateOrdinal = p.moveState.ordinal();
            this.routeTargetIndex = p.routeTargetIndex;
        }

        boolean hasMovedSignificantly(Passenger p) {
            if (this.moveStateOrdinal != p.moveState.ordinal()) return true;
            if (this.routeTargetIndex != p.routeTargetIndex) return true;
            double dx = this.x - p.x;
            double dy = this.y - p.y;
            double dz = this.z - p.z;
            return (dx * dx + dy * dy + dz * dz) > 0.01; // 0.1ブロック以上動いた場合のみ差分送信
        }
    }

    /**
     * ★改善: 重い NBT 変換処理を完全にバックグラウンドスレッドに移行し、
     * さらに関係のない位置にいる乗客や「動いていない乗客」のパケットをカットして差分送信します。
     */
    public static void sendPassengerList(ServerPlayerEntity player) {
        if (player == null) return;

        String playerUuid = player.getUuidAsString();
        String playerWorldId = player.getWorld().getRegistryKey().getValue().toString();

        // プレイヤーの座標情報を取得（メインスレッドでのみ安全に読めるデータ）
        double pX = player.getX();
        double pY = player.getY();
        double pZ = player.getZ();

        // 走査用に現在の乗客リストのクローン（シャローコピー）を作成
        List<Passenger> passengersToProcess = new ArrayList<>(PassengerManager.PASSENGER_LIST);

        // NBTエンコード処理を丸ごと別スレッドへ排他実行
        PACKET_SERIALIZE_EXECUTOR.submit(() -> {
            try {
                List<NbtCompound> serializedData = new ArrayList<>();
                Map<Long, LastSentState> playerCache = lastSentPositions.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());

                for (Passenger p : passengersToProcess) {
                    if (p == null || p.worldId == null) continue;

                    // 1. 同一ディメンションチェック
                    if (!p.worldId.equals(playerWorldId)) continue;

                    // 2. 超高速近接カリング（距離チェック）
                    if (p.moveState == Passenger.MoveState.ON_TRAIN) {
                        double dBoardX = Double.isNaN(p.boardingX) ? pX : p.boardingX;
                        double dBoardY = Double.isNaN(p.boardingY) ? pY : p.boardingY;
                        double dBoardZ = Double.isNaN(p.boardingZ) ? pZ : p.boardingZ;
                        double dx = pX - dBoardX;
                        double dy = pY - dBoardY;
                        double dz = pZ - dBoardZ;
                        if ((dx * dx + dy * dy + dz * dz) > 256.0 * 256.0) continue;
                    } else {
                        double dx = pX - p.x;
                        double dy = pY - p.y;
                        double dz = pZ - p.z;
                        if ((dx * dx + dy * dy + dz * dz) > 128.0 * 128.0) continue;
                    }

                    // 3. 動的差分同期（Delta Sync）
                    // 座標が前回送信時とほとんど変わっていない待機中の乗客は、パケット生成をスキップ！
                    LastSentState lastState = playerCache.get(p.id);
                    if (lastState != null && !lastState.hasMovedSignificantly(p)) {
                        continue;
                    }

                    // キャッシュ更新とシリアライズ
                    playerCache.put(p.id, new LastSentState(p));
                    serializedData.add(p.toNbt());
                }

                // 送るデータが1件もない場合はパケット送信自体をスルー
                if (serializedData.isEmpty()) return;

                // 送信バッファの構築
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeInt(serializedData.size());
                for (NbtCompound nbt : serializedData) {
                    buf.writeNbt(nbt);
                }

                // マイクラのネットワーク送信APIのみメインスレッドに戻して実行
                player.getServer().execute(() -> {
                    if (player.networkHandler != null) {
                        ServerPlayNetworking.send(player, SYNC_PASSENGER_LIST, buf);
                    }
                });

            } catch (Throwable t) {
                Rcap.LOGGER.error("[RCAP] 非同期パケット生成中にエラーが発生しました", t);
            }
        });
    }

    public static void sendCompanyList(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        List<Company> companies = CompanyManager.COMPANY_LIST;
        buf.writeInt(companies.size());
        for (Company c : companies) {
            buf.writeNbt(c.toNBT());
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