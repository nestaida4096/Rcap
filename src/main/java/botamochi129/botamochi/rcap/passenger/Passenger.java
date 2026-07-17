package botamochi129.botamochi.rcap.passenger;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class Passenger {
    public long id;
    public String name;
    public double x, y, z;
    public int color;
    public int skinIndex;

    // --- ルート・目的地関連 ---
    /** 移動ルートとしてのプラットフォームIDリスト */
    public List<Long> route = new ArrayList<>();
    /** 各プラットフォーム step で次に乗るべき routeId。徒歩 step や終点は -1 */
    public final List<Long> boardingRouteIds = new ArrayList<>();

    /** 現在目標としているルートのインデックス */
    public int routeTargetIndex = 0;

    /** 移動状態 */
    public enum MoveState {
        WALKING_TO_PLATFORM,
        WAITING_FOR_TRAIN,
        ON_TRAIN,
        WALKING_TO_DESTINATION,
        IDLE,
        WAITING_AT_DESTINATION
    }
    public MoveState moveState = MoveState.IDLE;

    public static final Identifier[] SKINS = {
            new Identifier("rcap", "textures/entity/passenger/custom_skin.png"),
            new Identifier("rcap", "textures/entity/passenger/custom_skin_2.png")
    };
    // ワールドID（Dimensionの名前など）
    public String worldId;

    // クライアントが利用するための（オプショナル）列車参照ID（クライアント側で埋める）
    public Long currentTrainId = null;
    // クライアントで検出した「何号車か」を保持する
    public int currentCarIndex = -1;

    // --- 追加フィールド（サーバがスケジュール情報からセットする） ---
    public long boardingTimeMillis = -1L;
    public long alightTimeMillis = -1L;
    public long boardingPlatformId = -1L;
    public long alightingPlatformId = -1L;
    public long scheduledRouteId = -1L;
    public int alightRouteIndex = -1;

    // 座標保存（フォールバック用）
    public double boardingX = Double.NaN, boardingY = Double.NaN, boardingZ = Double.NaN;
    public double alightX = Double.NaN, alightY = Double.NaN, alightZ = Double.NaN;

    // 最終目的地（オフィス）および帰宅先座標
    public double destinationX = Double.NaN, destinationY = Double.NaN, destinationZ = Double.NaN;
    public double homeX = Double.NaN, homeY = Double.NaN, homeZ = Double.NaN;
    public final List<Long> returnRoute = new ArrayList<>();
    public final List<Long> returnBoardingRouteIds = new ArrayList<>();
    public boolean returningHome = false;
    public long destinationWaitUntilMillis = -1L;
    public boolean commuteTrip = true;

    // パスファインディング経路保持用（スレッドセーフ）
    public final List<Long> walkPath = new java.util.concurrent.CopyOnWriteArrayList<>();
    public int walkPathIndex = 0;
    public long walkTargetKey = Long.MIN_VALUE;

    public long lastAlightedRouteId = -1L;
    public long lastAlightedAtMillis = -1L;

    // --- スタック防止・放置救済用の追加フィールド (シリアライズ不要な動的状態) ---
    /** 前回の更新時のX座標 */
    public double lastTickX = Double.NaN;
    /** 前回の更新時のZ座標 */
    public double lastTickZ = Double.NaN;
    /** 同じ場所に留まり続けている（動けていない）Tick数 */
    public int stuckTicks = 0;
    /** IDLE状態になってからのシステム時刻（ミリ秒）*/
    public long idleStartTimeMillis = -1L;

    // Watchdogフリーズ防止用の最終経路探索実行時刻（Tick数）
    public transient long lastPathfindTick = -1L;

    // 非同期で経路探索が実行中かどうかを管理するスレッドセーフなフラグ
    public transient volatile boolean isPathfinding = false;

    // ★改善：リーダー追従（群衆トレイル）用の動的メモリ領域（シリアライズ不要なため transient）
    /** 自身がリーダーである場合、フォロワーに分け与えるために記録し続ける実座標の軌跡 */
    public transient final List<Vec3d> positionTrail = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 自身がフォロワーである場合、追従対象としているリーダー乗客のID */
    public transient long leaderPassengerId = -1L;
    /** グループ内における自分のフォロワー順位（デシインデックス） */
    public transient int followerRank = 0;

    public Passenger(long id, String name, double x, double y, double z, int color, String worldId) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.skinIndex = (int) (Math.random() * SKINS.length);
        this.worldId = worldId;
    }

    /** NBTシリアライズ */
    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putLong("id", id);
        tag.putString("name", name);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putInt("color", color);
        tag.putString("worldId", worldId);

        NbtList listTag = new NbtList();
        for (Long platformId : route) {
            listTag.add(NbtLong.of(platformId));
        }
        tag.put("route", listTag);

        NbtList boardingRouteTag = new NbtList();
        for (Long routeId : boardingRouteIds) {
            boardingRouteTag.add(NbtLong.of(routeId));
        }
        tag.put("boardingRouteIds", boardingRouteTag);

        tag.putInt("routeTargetIndex", routeTargetIndex);
        tag.putInt("moveState", moveState.ordinal());
        tag.putInt("skinIndex", skinIndex);

        tag.putLong("boardingTimeMillis", boardingTimeMillis);
        tag.putLong("alightTimeMillis", alightTimeMillis);
        tag.putLong("boardingPlatformId", boardingPlatformId);
        tag.putLong("alightingPlatformId", alightingPlatformId);
        tag.putLong("scheduledRouteId", scheduledRouteId);
        tag.putInt("alightRouteIndex", alightRouteIndex);

        tag.putDouble("boardingX", boardingX);
        tag.putDouble("boardingY", boardingY);
        tag.putDouble("boardingZ", boardingZ);
        tag.putDouble("alightX", alightX);
        tag.putDouble("alightY", alightY);
        tag.putDouble("alightZ", alightZ);

        tag.putDouble("destinationX", destinationX);
        tag.putDouble("destinationY", destinationY);
        tag.putDouble("destinationZ", destinationZ);
        tag.putDouble("homeX", homeX);
        tag.putDouble("homeY", homeY);
        tag.putDouble("homeZ", homeZ);

        NbtList returnRouteTag = new NbtList();
        for (Long platformId : returnRoute) {
            returnRouteTag.add(NbtLong.of(platformId));
        }
        tag.put("returnRoute", returnRouteTag);
        NbtList returnBoardingRouteTag = new NbtList();
        for (Long routeId : returnBoardingRouteIds) {
            returnBoardingRouteTag.add(NbtLong.of(routeId));
        }
        tag.put("returnBoardingRouteIds", returnBoardingRouteTag);
        tag.putBoolean("returningHome", returningHome);
        tag.putLong("destinationWaitUntilMillis", destinationWaitUntilMillis);
        tag.putBoolean("commuteTrip", commuteTrip);
        tag.putLong("lastAlightedRouteId", lastAlightedRouteId);
        tag.putLong("lastAlightedAtMillis", lastAlightedAtMillis);

        return tag;
    }

    /** NBTデシリアライズ */
    public static Passenger fromNbt(NbtCompound tag) {
        Passenger p = new Passenger(
                tag.getLong("id"),
                tag.getString("name"),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getInt("color"),
                tag.contains("worldId") ? tag.getString("worldId") : "minecraft:overworld"
        );

        p.route.clear();
        if (tag.contains("route")) {
            NbtList routeList = tag.getList("route", NbtElement.LONG_TYPE);
            for (int i = 0; i < routeList.size(); i++) {
                var element = routeList.get(i);
                if (element instanceof NbtLong nbtLong) {
                    p.route.add(nbtLong.longValue());
                }
            }
        }
        p.boardingRouteIds.clear();
        if (tag.contains("boardingRouteIds")) {
            NbtList boardingRouteList = tag.getList("boardingRouteIds", NbtElement.LONG_TYPE);
            for (int i = 0; i < boardingRouteList.size(); i++) {
                var element = boardingRouteList.get(i);
                if (element instanceof NbtLong nbtLong) {
                    p.boardingRouteIds.add(nbtLong.longValue());
                }
            }
        }
        while (p.boardingRouteIds.size() < p.route.size()) {
            p.boardingRouteIds.add(-1L);
        }

        p.routeTargetIndex = tag.getInt("routeTargetIndex");

        int moveStateOrdinal = tag.getInt("moveState");
        if (moveStateOrdinal >= 0 && moveStateOrdinal < MoveState.values().length) {
            p.moveState = MoveState.values()[moveStateOrdinal];
        } else {
            p.moveState = MoveState.IDLE;
        }
        p.skinIndex = tag.contains("skinIndex") ? tag.getInt("skinIndex") : (int)(Math.random() * SKINS.length);

        p.boardingTimeMillis = tag.contains("boardingTimeMillis") ? tag.getLong("boardingTimeMillis") : -1L;
        p.alightTimeMillis = tag.contains("alightTimeMillis") ? tag.getLong("alightTimeMillis") : -1L;
        p.boardingPlatformId = tag.contains("boardingPlatformId") ? tag.getLong("boardingPlatformId") : -1L;
        p.alightingPlatformId = tag.contains("alightingPlatformId") ? tag.getLong("alightingPlatformId") : -1L;
        p.scheduledRouteId = tag.contains("scheduledRouteId") ? tag.getLong("scheduledRouteId") : -1L;
        p.alightRouteIndex = tag.contains("alightRouteIndex") ? tag.getInt("alightRouteIndex") : -1;

        p.boardingX = tag.contains("boardingX") ? tag.getDouble("boardingX") : Double.NaN;
        p.boardingY = tag.contains("boardingY") ? tag.getDouble("boardingY") : Double.NaN;
        p.boardingZ = tag.contains("boardingZ") ? tag.getDouble("boardingZ") : Double.NaN;
        p.alightX = tag.contains("alightX") ? tag.getDouble("alightX") : Double.NaN;
        p.alightY = tag.contains("alightY") ? tag.getDouble("alightY") : Double.NaN;
        p.alightZ = tag.contains("alightZ") ? tag.getDouble("alightZ") : Double.NaN;

        p.destinationX = tag.contains("destinationX") ? tag.getDouble("destinationX") : Double.NaN;
        p.destinationY = tag.contains("destinationY") ? tag.getDouble("destinationY") : Double.NaN;
        p.destinationZ = tag.contains("destinationZ") ? tag.getDouble("destinationZ") : Double.NaN;
        p.homeX = tag.contains("homeX") ? tag.getDouble("homeX") : Double.NaN;
        p.homeY = tag.contains("homeY") ? tag.getDouble("homeY") : Double.NaN;
        p.homeZ = tag.contains("homeZ") ? tag.getDouble("homeZ") : Double.NaN;
        p.returnRoute.clear();
        if (tag.contains("returnRoute")) {
            NbtList returnRouteList = tag.getList("returnRoute", NbtElement.LONG_TYPE);
            for (int i = 0; i < returnRouteList.size(); i++) {
                var element = returnRouteList.get(i);
                if (element instanceof NbtLong nbtLong) {
                    p.returnRoute.add(nbtLong.longValue());
                }
            }
        }
        p.returnBoardingRouteIds.clear();
        if (tag.contains("returnBoardingRouteIds")) {
            NbtList returnBoardingRouteList = tag.getList("returnBoardingRouteIds", NbtElement.LONG_TYPE);
            for (int i = 0; i < returnBoardingRouteList.size(); i++) {
                var element = returnBoardingRouteList.get(i);
                if (element instanceof NbtLong nbtLong) {
                    p.returnBoardingRouteIds.add(nbtLong.longValue());
                }
            }
        }
        while (p.returnBoardingRouteIds.size() < p.returnRoute.size()) {
            p.returnBoardingRouteIds.add(-1L);
        }
        p.returningHome = tag.contains("returningHome") && tag.getBoolean("returningHome");
        p.destinationWaitUntilMillis = tag.contains("destinationWaitUntilMillis") ? tag.getLong("destinationWaitUntilMillis") : -1L;
        p.commuteTrip = !tag.contains("commuteTrip") || tag.getBoolean("commuteTrip");
        p.lastAlightedRouteId = tag.contains("lastAlightedRouteId") ? tag.getLong("lastAlightedRouteId") : -1L;
        p.lastAlightedAtMillis = tag.contains("lastAlightedAtMillis") ? tag.getLong("lastAlightedAtMillis") : -1L;

        p.currentTrainId = null;
        p.currentCarIndex = -1;

        return p;
    }
}