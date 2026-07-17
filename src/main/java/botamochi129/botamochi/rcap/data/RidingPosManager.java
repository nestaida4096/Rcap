package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class RidingPosManager {

    // ★バグ修正：Listの代わりに Set を使用し、毎ティック呼ばれても絶対に重複登録されないようにする
    private static final Map<Long, Set<RidingPosBlockEntity>> platformIdToRidingPosMap = new ConcurrentHashMap<>();

    // RidingPosBlockEntity を登録するメソッド
    public static void registerRidingPos(RidingPosBlockEntity ridingPos) {
        long platformId = ridingPos.getPlatformId();
        // Set.add は既に存在する場合は何もしないので、毎ティック呼ばれても無限増殖しません
        platformIdToRidingPosMap.computeIfAbsent(platformId, k -> new CopyOnWriteArraySet<>()).add(ridingPos);
    }

    // RidingPosBlockEntity を解除するメソッド
    public static void unregisterRidingPos(RidingPosBlockEntity ridingPos) {
        long platformId = ridingPos.getPlatformId();
        Set<RidingPosBlockEntity> set = platformIdToRidingPosMap.get(platformId);
        if (set != null) {
            set.remove(ridingPos);
            if (set.isEmpty()) {
                platformIdToRidingPosMap.remove(platformId);
            }
        }
    }

    // プラットフォームID から乗車位置リストを取得（PassengerMovement 互換のために List に変換して返す）
    public static List<RidingPosBlockEntity> getRidingPositions(long platformId) {
        Set<RidingPosBlockEntity> set = platformIdToRidingPosMap.get(platformId);
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(set);
    }
}