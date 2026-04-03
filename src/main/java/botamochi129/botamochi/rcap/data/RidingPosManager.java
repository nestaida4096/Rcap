package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;

import java.util.*;

public class RidingPosManager {

    // プラットフォームID をキーに対応する乗車位置リストを保持
    private static final Map<Long, List<RidingPosBlockEntity>> platformIdToRidingPosMap = new HashMap<>();

    // RidingPosBlockEntity を登録するメソッド
    public static void registerRidingPos(RidingPosBlockEntity ridingPos) {
        long platformId = ridingPos.getPlatformId();
        platformIdToRidingPosMap.computeIfAbsent(platformId, k -> new ArrayList<>()).add(ridingPos);
    }

    // RidingPosBlockEntity を解除するメソッド
    public static void unregisterRidingPos(RidingPosBlockEntity ridingPos) {
        long platformId = ridingPos.getPlatformId();
        List<RidingPosBlockEntity> list = platformIdToRidingPosMap.get(platformId);
        if (list != null) {
            list.remove(ridingPos);
            if (list.isEmpty()) {
                platformIdToRidingPosMap.remove(platformId);
            }
        }
    }

    // プラットフォームID から乗車位置リストを取得
    public static List<RidingPosBlockEntity> getRidingPositions(long platformId) {
        return platformIdToRidingPosMap.getOrDefault(platformId, Collections.emptyList());
    }

    // ワールドのロード・アンロードやチャンク状態に応じて適宜管理するメソッドなども追加可
    // 必要に応じて更新処理やキャッシュクリアも
}
