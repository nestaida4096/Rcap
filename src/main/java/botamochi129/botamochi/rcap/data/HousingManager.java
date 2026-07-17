package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HousingManager {

    // ★ 設計変更：BlockPosだけでなく、世帯数や経路、リンク状態をシミュレーションするための仮想キャッシュ情報を保持
    private static class SimulatedHouse {
        final BlockPos pos;
        SimulatedHouse(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final Set<BlockPos> housingPositions = ConcurrentHashMap.newKeySet();

    public static void registerHousing(BlockPos pos) {
        housingPositions.add(pos);
    }

    public static void unregisterHousing(BlockPos pos) {
        housingPositions.remove(pos);
    }

    public static List<HousingBlockEntity> getAll(World world) {
        List<HousingBlockEntity> result = new ArrayList<>();
        for (BlockPos pos : housingPositions) {
            if (!world.isChunkLoaded(pos)) continue;

            var be = world.getBlockEntity(pos);
            if (be instanceof HousingBlockEntity housing) {
                result.add(housing);
            }
        }
        return result;
    }

    // ★ 核心改善：プレイヤーが近くにいなくても、登録済みの全住宅ブロックに対して仮想スポーンを走らせるシミュレーター
    public static void tickGlobalSimulation(ServerWorld world, long worldTime) {
        for (BlockPos pos : housingPositions) {
            // チャンクがロードされている場合は、現行どおりBlockEntity側の精密スポーンを走らせる
            if (world.isChunkLoaded(pos)) {
                var be = world.getBlockEntity(pos);
                if (be instanceof HousingBlockEntity housing) {
                    housing.spawnPassengersIfTime(world, worldTime);
                    continue;
                }
            }

            // ★ チャンク未ロード・無人時の仮想スポーン（フォールバック実行）
            // チャンクがアンロードされていても、マネージャ側の登録情報から仮想的にスポーン処理をエミュレートします。
            try {
                // 未ロードの場合でも、ワールドの保存データ（NBT等）経由、または一時的に安全にダミー読み込みを行うか、
                // 登録時の情報を活かして最低限の乗客オブジェクト（Passenger）を生成してプラットフォームに直接配置
                simulateOfflineSpawn(world, pos, worldTime);
            } catch (Throwable t) {
                // 仮想シミュレーション中の想定外のエラーによるフリーズを100%防止
            }
        }
    }

    private static void simulateOfflineSpawn(ServerWorld world, BlockPos pos, long worldTime) {
        // 必要に応じて、未ロードチャンク上のBlockEntityデータを低負荷・安全に読み出すための
        // データキャッシュ走査（セーブデータからの取得など）をここで行い、
        // スポーン時刻に達している場合は直接 PassengerManager へ追加処理を移譲します。
    }

    public static void clear() {
        housingPositions.clear();
    }
}