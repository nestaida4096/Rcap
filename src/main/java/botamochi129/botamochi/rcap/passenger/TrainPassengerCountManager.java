package botamochi129.botamochi.rcap.passenger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 列車（Train ID）ごとに現在乗車している乗客の人数を保有・管理するクラス。
 */
public class TrainPassengerCountManager {
    // 列車ID -> 乗車している乗客数
    private static final Map<Long, Integer> PASSENGER_COUNTS = new ConcurrentHashMap<>();

    /**
     * 指定された列車の乗客人数を設定します。
     */
    public static void setCount(long trainId, int count) {
        if (count <= 0) {
            PASSENGER_COUNTS.remove(trainId);
        } else {
            PASSENGER_COUNTS.put(trainId, count);
        }
    }

    /**
     * 指定された列車に乗っている乗客人数を取得します。
     */
    public static int getCount(long trainId) {
        return PASSENGER_COUNTS.getOrDefault(trainId, 0);
    }

    /**
     * 指定された列車の乗客人数を 1 増やします。
     */
    public static void increment(long trainId) {
        PASSENGER_COUNTS.merge(trainId, 1, Integer::sum);
    }

    /**
     * 指定された列車の乗客人数を 1 減らします。
     */
    public static void decrement(long trainId) {
        PASSENGER_COUNTS.computeIfPresent(trainId, (k, v) -> v > 1 ? v - 1 : null);
    }

    /**
     * 管理しているすべての乗客人数データをクリアします。
     */
    public static void clear() {
        PASSENGER_COUNTS.clear();
    }

    /**
     * 内部の Map インスタンスを直接取得します。
     */
    public static Map<Long, Integer> getPassengerCounts() {
        return PASSENGER_COUNTS;
    }
}