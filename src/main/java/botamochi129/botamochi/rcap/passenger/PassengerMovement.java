package botamochi129.botamochi.rcap.passenger;

import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;
import botamochi129.botamochi.rcap.data.RidingPosManager;
import botamochi129.botamochi.rcap.mixin.TrainAccessor;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import mtr.data.Siding;
import mtr.data.Train;
import mtr.data.TrainServer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static botamochi129.botamochi.rcap.passenger.PassengerPathfinder.isSpaceClearAt;

public class PassengerMovement {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final long EVENING_RUSH_START_TICKS = 10_000L;
    private static final long EVENING_RUSH_END_TICKS = 16_000L;

    // 探索最大半径を32に設定。歩行パケットとして十分な長さ。
    private static final int WALK_PATH_MAX_DISTANCE = 32;
    private static final double TRAIN_PLATFORM_MATCH_DISTANCE_SQ = 225.0; // 判定距離を12mから15mへ少し緩めて検出しやすく修正 (144.0 -> 225.0)
    private static final double[] WALKING_LANES = {-0.45, -0.25, 0.0, 0.25, 0.45};

    private static final int STUCK_THRESHOLD_TICKS = 200; // 約10秒間動けない場合にスタックと判定
    private static final double STUCK_MOVE_THRESHOLD_SQ = 0.0025; // 0.05ブロック以下の移動
    private static final long IDLE_CLEANUP_TIMEOUT_MILLIS = 60_000L; // IDLEが60秒継続した場合はクリーンアップ

    // 経路探索専用の軽量スレッドプール
    private static final ForkJoinPool PATHFIND_EXECUTOR = new ForkJoinPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4)
    );

    // 優先改善①：ThreadSafeRegionView のキャッシュ構造
    private static final Map<Long, ThreadSafeRegionView> REGION_VIEW_CACHE = new HashMap<>();

    // 優先改善②：目的地ごとの経路探索結果キャッシュ
    private static class CachedPath {
        final List<Long> path;
        final long creationTime;

        CachedPath(List<Long> path, long creationTime) {
            this.path = path;
            this.creationTime = creationTime;
        }
    }
    private static final Map<Long, CachedPath> PATH_CACHE_BY_TARGET = new HashMap<>();
    private static final long PATH_CACHE_EXPIRY_MS = 10_000L; // 経路キャッシュの寿命（10秒）

    // 優先改善④：新規スナップショット生成の複数ティック分割・制限（レートリミッター）
    private static final int MAX_NEW_SNAPSHOTS_PER_TICK = 2;
    private static int newSnapshotsGeneratedThisTick = 0;

    // ★ラグ対策：1ティック内における電車の走査・リフレクション結果を一時キャッシュする構造
    private static final Map<Long, TrainServer> STOPPED_TRAIN_CACHE = new HashMap<>();
    private static final Map<String, TrainServer> BOARDABLE_TRAIN_CACHE = new HashMap<>();
    private static final Map<String, AlightPlan> ALIGHT_PLAN_CACHE = new HashMap<>();

    // ★号車の座標（リフレクション計算）を1Tickに1回だけ行って共有するためのキャッシュ
    private static final Map<String, Vec3d> TRAIN_CAR_POS_CACHE = new HashMap<>();

    // ★リフレクション（Java Reflection）のボトルネック解消用キャッシュ
    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();

    // ★大混雑対策：1プラットフォームあたり、1ティック内で同時に乗車(ON_TRAINへ遷移)できる最大乗客数
    private static final int MAX_BOARDING_PER_PLATFORM_PER_TICK = 4;
    private static final Map<Long, Integer> PLATFORM_BOARDING_COUNT_THIS_TICK = new HashMap<>();

    // 非同期スレッドからブロック情報を安全に取得するためのスナップショット
    private static class ThreadSafeRegionView implements net.minecraft.world.BlockView {
        private final BlockState[] blockCache;
        private final int minX, minY, minZ;
        private final int sizeX, sizeY, sizeZ;
        private final int bottomY;
        private final int height;

        public ThreadSafeRegionView(ServerWorld world, BlockPos start, int radius) {
            this.bottomY = world.getBottomY();
            this.height = world.getHeight();

            int range = radius + 2;
            this.minX = start.getX() - range;
            this.minY = Math.max(world.getBottomY(), start.getY() - 8);
            this.minZ = start.getZ() - range;

            int maxX = start.getX() + range;
            int maxY = Math.min(world.getTopY(), start.getY() + 8);
            int maxZ = start.getZ() + range;

            this.sizeX = maxX - this.minX + 1;
            this.sizeY = maxY - this.minY + 1;
            this.sizeZ = maxZ - this.minZ + 1;

            this.blockCache = new BlockState[this.sizeX * this.sizeY * this.sizeZ];

            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int x = 0; x < this.sizeX; x++) {
                int currentWorldX = this.minX + x;
                for (int z = 0; z < this.sizeZ; z++) {
                    int currentWorldZ = this.minZ + z;

                    if (world.getChunkManager().isChunkLoaded(currentWorldX >> 4, currentWorldZ >> 4)) {
                        for (int y = 0; y < this.sizeY; y++) {
                            mutable.set(currentWorldX, this.minY + y, currentWorldZ);
                            this.blockCache[x + y * this.sizeX + z * this.sizeX * this.sizeY] =
                                    world.getBlockState(mutable);
                        }
                    } else {
                        for (int y = 0; y < this.sizeY; y++) {
                            this.blockCache[x + y * this.sizeX + z * this.sizeX * this.sizeY] =
                                    Blocks.AIR.getDefaultState();
                        }
                    }
                }
            }
        }

        @Override
        public net.minecraft.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int x = pos.getX() - this.minX;
            int y = pos.getY() - this.minY;
            int z = pos.getZ() - this.minZ;

            if (x >= 0 && x < this.sizeX && y >= 0 && y < this.sizeY && z >= 0 && z < this.sizeZ) {
                BlockState state = this.blockCache[x + y * this.sizeX + z * this.sizeX * this.sizeY];
                return state != null ? state : Blocks.AIR.getDefaultState();
            }
            return Blocks.AIR.getDefaultState();
        }

        @Override
        public net.minecraft.fluid.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.fluid.Fluids.EMPTY.getDefaultState();
        }

        @Override
        public int getBottomY() {
            return bottomY;
        }

        @Override
        public int getHeight() {
            return height;
        }
    }

    public static void prepareTick() {
        REGION_VIEW_CACHE.clear();
        newSnapshotsGeneratedThisTick = 0;

        long now = System.currentTimeMillis();
        PATH_CACHE_BY_TARGET.entrySet().removeIf(entry -> (now - entry.getValue().creationTime) > PATH_CACHE_EXPIRY_MS);

        // ティック開始時にすべての一時キャッシュをフラッシュ
        STOPPED_TRAIN_CACHE.clear();
        BOARDABLE_TRAIN_CACHE.clear();
        ALIGHT_PLAN_CACHE.clear();
        TRAIN_CAR_POS_CACHE.clear();
        PLATFORM_BOARDING_COUNT_THIS_TICK.clear();
    }

    public static void updatePassenger(ServerWorld world, Passenger passenger, RailwayData railwayData) {
        Passenger.MoveState prevState = passenger.moveState;

        try {
            updatePassengerInternal(world, passenger, railwayData);
        } finally {
            if (botamochi129.botamochi.rcap.Rcap.passengerStateLogEnabled && prevState != passenger.moveState) {
                MinecraftServer server = world.getServer();
                if (server != null) {
                    net.minecraft.text.Text msg = net.minecraft.text.Text.literal(
                            "§b[Passenger Log] §f" + passenger.name + " (" + passenger.id + "): §7" + prevState + " §f-> §a" + passenger.moveState
                    );
                    server.getPlayerManager().broadcast(msg, false);
                }
            }
        }
    }

    private static void logToChat(ServerWorld world, String message) {
        if (botamochi129.botamochi.rcap.Rcap.passengerStateLogEnabled) {
            MinecraftServer server = world.getServer();
            if (server != null) {
                server.getPlayerManager().broadcast(Text.literal("§e[Boarding Debug] §f" + message), false);
            }
        }
    }

    private static void updatePassengerInternal(ServerWorld world, Passenger passenger, RailwayData railwayData) {
        LOGGER.debug("[Passenger] {} updatePassenger called. world={}", passenger.name, world.getRegistryKey().getValue());

        if (passenger.moveState == Passenger.MoveState.IDLE) {
            long nowTime = System.currentTimeMillis();
            if (passenger.idleStartTimeMillis <= 0) {
                passenger.idleStartTimeMillis = nowTime;
            } else if (nowTime - passenger.idleStartTimeMillis > IDLE_CLEANUP_TIMEOUT_MILLIS) {
                LOGGER.info("[Passenger] {} has been IDLE too long. Auto-despawning.", passenger.id);
                removePassengerSafely(world, passenger);
                return;
            }
        } else {
            passenger.idleStartTimeMillis = -1L;
        }

        List<Long> route = passenger.route;
        List<Long> boardingRouteIds = passenger.boardingRouteIds;

        if (route == null || route.isEmpty() || passenger.routeTargetIndex >= route.size()) {
            if (passenger.moveState != Passenger.MoveState.IDLE) {
                passenger.moveState = Passenger.MoveState.IDLE;
            }
            return;
        }

        long targetPlatformId = route.get(passenger.routeTargetIndex);
        long expectedRouteId = getExpectedBoardingRouteId(passenger, boardingRouteIds);

        if (railwayData.dataCache.platformIdMap.isEmpty()) {
            LOGGER.warn("[Passenger] RailwayData cache is empty for passenger {}", passenger.id);
            return;
        }

        if (!railwayData.dataCache.platformIdMap.containsKey(targetPlatformId)) {
            LOGGER.warn("[Passenger] targetPlatformId {} not found. Setting to IDLE.", targetPlatformId);
            passenger.moveState = Passenger.MoveState.IDLE;
            return;
        }
        Platform platform = railwayData.dataCache.platformIdMap.get(targetPlatformId);
        if (platform == null) {
            passenger.moveState = Passenger.MoveState.IDLE;
            return;
        }

        BlockPos targetPosBlock = null;
        var ridingPositions = RidingPosManager.getRidingPositions(targetPlatformId);

        boolean hasArrivedAtRidingPos = (passenger.moveState == Passenger.MoveState.WAITING_FOR_TRAIN);

        if (ridingPositions != null && !ridingPositions.isEmpty()) {
            TrainServer stoppedTrain = getTrainStoppedAtPlatform(railwayData, platform);

            if (stoppedTrain != null && (hasArrivedAtRidingPos || stoppedTrain.getDoorValue() > 0.01F)) {
                int carCount = Math.max(1, getTrainCarCount(stoppedTrain));
                int assignedCarIndex = Math.floorMod(passenger.id, carCount);

                Vec3d carPos = getCachedCarPosition(stoppedTrain, assignedCarIndex);

                if (carPos != null) {
                    RidingPosBlockEntity bestRidingPos = null;
                    double bestDistSq = Double.MAX_VALUE;
                    for (RidingPosBlockEntity rp : ridingPositions) {
                        double rpX = rp.getPos().getX() + 0.5;
                        double rpY = rp.getPos().getY() + 0.5;
                        double rpZ = rp.getPos().getZ() + 0.5;
                        double distSq = carPos.squaredDistanceTo(rpX, rpY, rpZ);

                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestRidingPos = rp;
                        }
                    }
                    if (bestRidingPos != null) {
                        targetPosBlock = bestRidingPos.getPos();
                    }
                }
            }

            if (targetPosBlock == null) {
                long ridingPosSeed = passenger.id ^ targetPlatformId ^ expectedRouteId ^ ((long) passenger.routeTargetIndex << 32);
                int index = Math.floorMod(Long.hashCode(ridingPosSeed), ridingPositions.size());
                targetPosBlock = ridingPositions.get(index).getPos();
            }
        } else {
            targetPosBlock = platform.getMidPos();
        }

        BlockPos standingTargetPos = resolveStandingPos(world, targetPosBlock);
        Vec3d targetPos = new Vec3d(standingTargetPos.getX() + 0.5, standingTargetPos.getY(), standingTargetPos.getZ() + 0.5);

        double dx = passenger.x - targetPos.x;
        double dz = passenger.z - targetPos.z;
        double distanceSqXZ = dx * dx + dz * dz;

        final double walkSpeed = 0.25;

        if (passenger.moveState != Passenger.MoveState.WAITING_FOR_TRAIN) {
            checkAndResolveStuck(world, passenger, targetPos);
        }

        switch (passenger.moveState) {
            case WALKING_TO_PLATFORM:
                if (passenger.routeTargetIndex == route.size() - 1 && distanceSqXZ < 0.16) {
                    LOGGER.info("[Passenger] {} reached final platform (index {}). Switching to WALKING_TO_DESTINATION.", passenger.id, passenger.routeTargetIndex);
                    if (!Double.isNaN(passenger.destinationX) && !Double.isNaN(passenger.destinationY) && !Double.isNaN(passenger.destinationZ)) {
                        passenger.moveState = Passenger.MoveState.WALKING_TO_DESTINATION;
                    } else {
                        LOGGER.info("[Passenger] {} destination unknown -> removing passenger", passenger.id);
                        removePassengerSafely(world, passenger);
                    }
                    return;
                }

                if (distanceSqXZ < 0.16) {
                    if (advanceIfSameStationTransfer(railwayData, passenger, targetPlatformId)) {
                        clearWalkPath(passenger);
                        LOGGER.debug("[Passenger] {} walking transfer within station from platform {} to index {}", passenger.id, targetPlatformId, passenger.routeTargetIndex);
                        return;
                    }
                    passenger.moveState = Passenger.MoveState.WAITING_FOR_TRAIN;
                    clearWalkPath(passenger);

                    passenger.x = targetPos.x;
                    double safeY = findWalkableY(world, targetPos.x, passenger.y, targetPos.z);
                    if (!Double.isNaN(safeY)) {
                        passenger.y = safeY;
                    }
                    passenger.z = targetPos.z;
                    LOGGER.debug("[Passenger] {} reached platform {} -> WAITING", passenger.id, targetPlatformId);
                } else {
                    walkTowards(world, passenger, standingTargetPos, walkSpeed);
                }
                break;

            case WAITING_FOR_TRAIN:
                if (advanceIfSameStationTransfer(railwayData, passenger, targetPlatformId)) {
                    passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
                    clearWalkPath(passenger);
                    LOGGER.debug("[Passenger] {} switched from WAITING to walking transfer at platform {}", passenger.id, targetPlatformId);
                    return;
                }

                // ★大混雑対策
                int boardingCount = PLATFORM_BOARDING_COUNT_THIS_TICK.getOrDefault(targetPlatformId, 0);
                if (boardingCount >= MAX_BOARDING_PER_PLATFORM_PER_TICK) {
                    passenger.x = targetPos.x;
                    passenger.y = passenger.y;
                    passenger.z = targetPos.z;
                    return;
                }

                // WAITING_FOR_TRAIN 状態を維持したまま、新たに指定された乗車位置へ滑らかに移動する
                if (distanceSqXZ > 0.16) {
                    walkTowards(world, passenger, standingTargetPos, walkSpeed);
                } else {
                    passenger.x = targetPos.x;
                    double safeY = findWalkableY(world, targetPos.x, passenger.y, targetPos.z);
                    if (!Double.isNaN(safeY)) {
                        passenger.y = safeY;
                    }
                    passenger.z = targetPos.z;
                    clearWalkPath(passenger);
                }

                long now = System.currentTimeMillis();
                List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(targetPlatformId);

                // 【デバッグ出力2】 schedulesがnullかどうかの確認
                if (schedules == null) {
                    logToChat(world, passenger.name + ": schedules is null at platform " + targetPlatformId);
                } else if (schedules.isEmpty()) {
                    logToChat(world, passenger.name + ": schedules is empty at platform " + targetPlatformId);
                }

                ScheduleEntry matched = null;
                AlightPlan alightPlan = null;

                if (schedules != null) {
                    for (ScheduleEntry s : schedules) {
                        try {
                            // 【デバッグ出力3】判定項目的ダンプ
                            boolean timeOk = (s.arrivalMillis - 10000L <= now); // 余裕を10秒に拡大 (5秒から変更)
                            boolean routeIdOk = (expectedRouteId <= 0 || s.routeId == expectedRouteId);

                            // ★物理車両停車チェックによる超強化: すでに目の前に物理的な列車が停車していれば、時刻判定をスキップして進める
                            TrainServer physTrain = findBoardableTrain(world, railwayData, targetPlatformId, s.routeId, targetPos);
                            if (physTrain != null) {
                                timeOk = true;
                                logToChat(world, passenger.name + ": Physically stopped train detected! Forcing timeOk = true");
                            }

                            logToChat(world, String.format(
                                    "%s: schedule: routeId=%d, expectedRouteId=%d, arrival=%d, now=%d, timeDiff=%d (timeOk=%b, routeIdOk=%b)",
                                    passenger.name, s.routeId, expectedRouteId, s.arrivalMillis, now, (now - s.arrivalMillis), timeOk, routeIdOk
                            ));

                            if (timeOk) {
                                if (!routeIdOk) {
                                    continue;
                                }
                                AlightPlan candidatePlan = findAlightPlan(railwayData, passenger, s);

                                logToChat(world, passenger.name + ": candidatePlan of routeId " + s.routeId + " is " + (candidatePlan == null ? "null" : "non-null (" + candidatePlan.platformId + ")"));

                                TrainServer boardableTrain = physTrain != null ? physTrain : findBoardableTrain(world, railwayData, targetPlatformId, s.routeId, targetPos);

                                logToChat(world, passenger.name + ": boardableTrain is " + (boardableTrain == null ? "null" : "ID:" + boardableTrain.id));

                                if (candidatePlan != null && boardableTrain != null) {
                                    matched = s;
                                    alightPlan = candidatePlan;
                                    passenger.currentTrainId = boardableTrain.id;
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            logToChat(world, passenger.name + ": exception in schedules loop: " + t.getMessage());
                        }
                    }
                }

                // 【デバッグ出力1】matched, alightPlanのチェック
                if (matched != null || alightPlan != null) {
                    logToChat(world, String.format(
                            "%s: WAITING check -> matched=%b, alightPlan=%b",
                            passenger.name, (matched != null), (alightPlan != null)
                    ));
                }

                if (matched != null && alightPlan != null) {
                    // ★乗車カウントのインクリメント
                    PLATFORM_BOARDING_COUNT_THIS_TICK.put(targetPlatformId, boardingCount + 1);

                    passenger.moveState = Passenger.MoveState.ON_TRAIN;
                    passenger.boardingTimeMillis = matched.arrivalMillis > 0 ? matched.arrivalMillis : now;
                    passenger.boardingPlatformId = targetPlatformId;
                    passenger.scheduledRouteId = matched.routeId;

                    passenger.boardingX = targetPos.x;
                    passenger.boardingY = targetPos.y;
                    passenger.boardingZ = targetPos.z;

                    passenger.alightTimeMillis = alightPlan.alightTimeMillis;
                    passenger.alightingPlatformId = alightPlan.platformId;
                    passenger.alightX = alightPlan.alightX;
                    passenger.alightY = alightPlan.alightY;
                    passenger.alightZ = alightPlan.alightZ;
                    passenger.alightRouteIndex = alightPlan.routeIndex;

                    TrainServer boardableTrain = findBoardableTrain(world, railwayData, targetPlatformId, matched.routeId, targetPos);
                    if (boardableTrain != null) {
                        passenger.currentTrainId = boardableTrain.id;
                        passenger.currentCarIndex = getClosestCarIndex(boardableTrain, targetPos);
                    }

                    LOGGER.info("[Passenger] {} boarded: routeId={}, boardingPlatform={}, alightPlatform={}, carIndex={}",
                            passenger.id, passenger.scheduledRouteId, passenger.boardingPlatformId, passenger.alightingPlatformId, passenger.currentCarIndex);
                }
                break;

            case ON_TRAIN:
                long cur = System.currentTimeMillis();
                if (passenger.alightTimeMillis > 0 && cur >= passenger.alightTimeMillis && isTrainReadyForAlight(world, railwayData, passenger)) {
                    if (passenger.alightRouteIndex >= 0 && passenger.alightRouteIndex < passenger.route.size() - 1) {
                        passenger.routeTargetIndex = passenger.alightRouteIndex;
                        passenger.lastAlightedRouteId = passenger.scheduledRouteId;
                        passenger.lastAlightedAtMillis = cur;
                        if (!Double.isNaN(passenger.alightX) && !Double.isNaN(passenger.alightY) && !Double.isNaN(passenger.alightZ)) {
                            passenger.x = passenger.alightX;
                            passenger.y = passenger.alightY;
                            passenger.z = passenger.alightZ;
                        }
                        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
                        clearWalkPath(passenger);
                        resetOnTrainState(passenger);
                        LOGGER.debug("[Passenger] {} alighted and will WALK to next platform (index {}).", passenger.id, passenger.routeTargetIndex);
                    } else {
                        LOGGER.info("[Passenger] {} alight at final platform -> switch to WALKING_TO_DESTINATION", passenger.id);
                        if (!Double.isNaN(passenger.alightX) && !Double.isNaN(passenger.alightY) && !Double.isNaN(passenger.alightZ)) {
                            passenger.x = passenger.alightX;
                            passenger.y = passenger.alightY;
                            passenger.z = passenger.alightZ;
                        }
                        passenger.lastAlightedRouteId = passenger.scheduledRouteId;
                        passenger.lastAlightedAtMillis = cur;
                        if (!Double.isNaN(passenger.destinationX) && !Double.isNaN(passenger.destinationY) && !Double.isNaN(passenger.destinationZ)) {
                            passenger.moveState = Passenger.MoveState.WALKING_TO_DESTINATION;
                            clearWalkPath(passenger);
                        } else {
                            LOGGER.info("[Passenger] {} destination unknown at final alight -> removing", passenger.id);
                            removePassengerSafely(world, passenger);
                        }
                    }
                }
                break;

            case WALKING_TO_DESTINATION:
                if (Double.isNaN(passenger.destinationX) || Double.isNaN(passenger.destinationY) || Double.isNaN(passenger.destinationZ)) {
                    LOGGER.info("[Passenger] {} walking to destination but destination unknown -> removing", passenger.id);
                    removePassengerSafely(world, passenger);
                    return;
                }
                Vec3d dest = new Vec3d(passenger.destinationX, passenger.destinationY, passenger.destinationZ);
                Vec3d curPos = new Vec3d(passenger.x, passenger.y, passenger.z);

                double dxDest = curPos.x - dest.x;
                double dzDest = curPos.z - dest.z;
                double dist2XZ = dxDest * dxDest + dzDest * dzDest;

                if (dist2XZ < 0.16) {
                    if (passenger.returningHome) {
                        LOGGER.info("[Passenger] {} reached home -> removing", passenger.id);
                        removePassengerSafely(world, passenger);
                        return;
                    }

                    passenger.moveState = Passenger.MoveState.WAITING_AT_DESTINATION;

                    passenger.x = dest.x;
                    double safeY = findWalkableY(world, dest.x, passenger.y, dest.z);
                    if (!Double.isNaN(safeY)) {
                        passenger.y = safeY;
                    }
                    passenger.z = dest.z;

                    if (passenger.commuteTrip) {
                        passenger.destinationWaitUntilMillis = -1L;
                        LOGGER.info("[Passenger] {} reached office -> waiting for evening rush return trip", passenger.id);
                    } else {
                        LOGGER.info("[Passenger] {} reached daytime destination -> waiting before return trip", passenger.id);
                    }
                    return;
                } else {
                    walkTowards(world, passenger, resolveStandingPos(world, new BlockPos(dest)), walkSpeed);
                }
                break;

            case WAITING_AT_DESTINATION:
                boolean shouldReturn = passenger.commuteTrip ? isEveningRush(world) : passenger.destinationWaitUntilMillis > 0 && System.currentTimeMillis() >= passenger.destinationWaitUntilMillis;
                if (shouldReturn) {
                    if (passenger.returnRoute.isEmpty() || Double.isNaN(passenger.homeX) || Double.isNaN(passenger.homeY) || Double.isNaN(passenger.homeZ)) {
                        LOGGER.info("[Passenger] {} return trip unavailable -> removing", passenger.id);
                        removePassengerSafely(world, passenger);
                        return;
                    }

                    passenger.route = new java.util.ArrayList<>(passenger.returnRoute);
                    passenger.boardingRouteIds.clear();
                    passenger.boardingRouteIds.addAll(passenger.returnBoardingRouteIds);
                    passenger.routeTargetIndex = 0;
                    passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
                    passenger.destinationX = passenger.homeX;
                    passenger.destinationY = passenger.homeY;
                    passenger.destinationZ = passenger.homeZ;
                    passenger.returningHome = true;
                    passenger.destinationWaitUntilMillis = -1L;
                    clearWalkPath(passenger);
                    resetOnTrainState(passenger);
                    LOGGER.info("[Passenger] {} starting return trip", passenger.id);
                }
                break;

            default:
                break;
        }
    }

    private static void checkAndResolveStuck(ServerWorld world, Passenger passenger, Vec3d targetPos) {
        if (passenger.moveState != Passenger.MoveState.WALKING_TO_PLATFORM &&
                passenger.moveState != Passenger.MoveState.WALKING_TO_DESTINATION) {
            passenger.stuckTicks = 0;
            passenger.lastTickX = Double.NaN;
            passenger.lastTickZ = Double.NaN;
            return;
        }

        if (passenger.isPathfinding) {
            return;
        }

        double currentX = passenger.x;
        double currentZ = passenger.z;

        if (!Double.isNaN(passenger.lastTickX) && !Double.isNaN(passenger.lastTickZ)) {
            double deltaX = currentX - passenger.lastTickX;
            double deltaZ = currentZ - passenger.lastTickZ;
            double distMovedSq = deltaX * deltaX + deltaZ * deltaZ;

            if (distMovedSq < STUCK_MOVE_THRESHOLD_SQ) {
                passenger.stuckTicks++;
            } else {
                passenger.stuckTicks = 0;
            }
        }

        passenger.lastTickX = currentX;
        passenger.lastTickZ = currentZ;

        if (passenger.stuckTicks >= STUCK_THRESHOLD_TICKS) {
            LOGGER.warn("[Passenger] {} is stuck at ({}, {}). Attempting recovery.", passenger.id, currentX, currentZ);

            double resolvedY = findWalkableY(world, targetPos.x, targetPos.y, targetPos.z);
            Vec3d finalTeleportPos = Double.isNaN(resolvedY) ? targetPos : new Vec3d(targetPos.x, resolvedY, targetPos.z);

            double distanceToTargetSq = new Vec3d(passenger.x, passenger.y, passenger.z).squaredDistanceTo(finalTeleportPos);
            if (distanceToTargetSq > 2500.0) {
                LOGGER.warn("[Passenger] {} target position is too far. Delaying recovery warp.", passenger.id);
                passenger.stuckTicks = STUCK_THRESHOLD_TICKS - 60;
                clearWalkPath(passenger);
                return;
            }

            if (isSafeStandingPos(world, finalTeleportPos)) {
                passenger.x = finalTeleportPos.x;
                passenger.y = finalTeleportPos.y;
                passenger.z = finalTeleportPos.z;
                passenger.stuckTicks = 0;
                clearWalkPath(passenger);
                LOGGER.info("[Passenger] {} teleported to safe ground target position due to being stuck.", passenger.id);
            } else {
                LOGGER.error("[Passenger] {} target position is unsafe. Despawning.", passenger.id);
                removePassengerSafely(world, passenger);
            }
        }
    }

    private static void resetOnTrainState(Passenger passenger) {
        passenger.currentTrainId = null;
        passenger.boardingTimeMillis = -1L;
        passenger.alightTimeMillis = -1L;
        passenger.boardingPlatformId = -1L;
        passenger.alightingPlatformId = -1L;
        passenger.scheduledRouteId = -1L;
        passenger.alightRouteIndex = -1;
        passenger.boardingX = passenger.boardingY = passenger.boardingZ = Double.NaN;
        passenger.alightX = passenger.alightY = passenger.alightZ = Double.NaN;
    }

    private static void removePassengerSafely(ServerWorld world, Passenger passenger) {
        PassengerManager.PASSENGER_LIST.remove(passenger);
        PassengerManager.save();
        MinecraftServer server = world.getServer();
        if (server != null) {
            PassengerManager.broadcastToAllPlayers(server);
        }
    }

    private static boolean isEveningRush(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % 24000L;
        return timeOfDay >= EVENING_RUSH_START_TICKS && timeOfDay <= EVENING_RUSH_END_TICKS;
    }

    private static boolean advanceIfSameStationTransfer(RailwayData railwayData, Passenger passenger, long currentPlatformId) {
        int nextIndex = passenger.routeTargetIndex + 1;
        if (nextIndex >= passenger.route.size()) {
            return false;
        }
        long nextPlatformId = passenger.route.get(nextIndex);
        if (!isWalkTransfer(railwayData, currentPlatformId, nextPlatformId)) {
            return false;
        }

        if (currentPlatformId == nextPlatformId) {
            passenger.routeTargetIndex = nextIndex;
            return true;
        }

        passenger.routeTargetIndex = nextIndex;
        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
        return true;
    }

    private static boolean isWalkTransfer(RailwayData railwayData, long currentPlatformId, long nextPlatformId) {
        if (currentPlatformId == nextPlatformId) {
            return false;
        }
        var currentStation = railwayData.dataCache.platformIdToStation.get(currentPlatformId);
        var nextStation = railwayData.dataCache.platformIdToStation.get(nextPlatformId);
        if (currentStation == null || nextStation == null) {
            return false;
        }
        if (currentStation.id == nextStation.id) {
            return true;
        }
        var connectingStations = railwayData.dataCache.stationIdToConnectingStations.get(currentStation);
        if (containsStationId(connectingStations, nextStation.id)) {
            return true;
        }
        connectingStations = railwayData.dataCache.stationIdToConnectingStations.get(nextStation);
        return containsStationId(connectingStations, currentStation.id);
    }

    private static boolean containsStationId(Iterable<? extends mtr.data.Station> stations, long stationId) {
        if (stations == null) {
            return false;
        }
        for (var station : stations) {
            if (station != null && station.id == stationId) {
                return true;
            }
        }
        return false;
    }

    private static AlightPlan findAlightPlan(RailwayData railwayData, Passenger passenger, ScheduleEntry boardingSchedule) {
        String cacheKey = passenger.id + "_" + boardingSchedule.routeId + "_" + boardingSchedule.currentStationIndex;
        if (ALIGHT_PLAN_CACHE.containsKey(cacheKey)) {
            return ALIGHT_PLAN_CACHE.get(cacheKey);
        }
        AlightPlan plan = findAlightPlanUncached(railwayData, passenger, boardingSchedule);
        ALIGHT_PLAN_CACHE.put(cacheKey, plan);
        return plan;
    }

    private static AlightPlan findAlightPlanUncached(RailwayData railwayData, Passenger passenger, ScheduleEntry boardingSchedule) {
        // 降車先（乗客の route リスト上の次の降車駅）の検証ロジックを確実に処理
        for (int alightIndex = passenger.routeTargetIndex + 1; alightIndex < passenger.route.size(); alightIndex++) {
            long alightPlatformId = passenger.route.get(alightIndex);

            Platform alightPlatform = railwayData.dataCache.platformIdMap.get(alightPlatformId);
            if (alightPlatform == null) {
                continue;
            }

            // ★超強化: 次の予定プラットフォームが別路線のホームであっても、今乗る路線と同じ駅(Station)であるなら、
            // その同じ駅の「今乗る路線側のプラットフォームID」を一時的・仮想的に検索し、そのホームに到着するかどうかを判定可能にします。
            long resolvedAlightPlatformId = alightPlatformId;
            var alightStation = railwayData.dataCache.platformIdToStation.get(alightPlatformId);
            if (alightStation != null) {
                // 同じ駅内のすべてのホームをチェックし、今乗っている路線のスケジュールが存在するホームがあれば、それを実際の降車予定駅に紐付けます
                for (Map.Entry<Long, mtr.data.Station> entry : railwayData.dataCache.platformIdToStation.entrySet()) {
                    if (entry.getValue() != null && entry.getValue().id == alightStation.id) {
                        long siblingPlatformId = entry.getKey();
                        List<ScheduleEntry> siblingSchedules = railwayData.getSchedulesAtPlatform(siblingPlatformId);
                        if (siblingSchedules != null) {
                            for (ScheduleEntry checkS : siblingSchedules) {
                                if (checkS.routeId == boardingSchedule.routeId && checkS.currentStationIndex > boardingSchedule.currentStationIndex) {
                                    resolvedAlightPlatformId = siblingPlatformId;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            Platform resolvedAlightPlatform = railwayData.dataCache.platformIdMap.get(resolvedAlightPlatformId);
            if (resolvedAlightPlatform == null) {
                resolvedAlightPlatform = alightPlatform;
            }

            List<ScheduleEntry> alightSchedules = railwayData.getSchedulesAtPlatform(resolvedAlightPlatformId);
            long bestArrival = Long.MAX_VALUE;

            if (alightSchedules != null) {
                for (ScheduleEntry alightSchedule : alightSchedules) {
                    try {
                        if (alightSchedule.routeId != boardingSchedule.routeId) {
                            continue;
                        }
                        // スケジュール予測の前後関係を判定
                        if (alightSchedule.currentStationIndex <= boardingSchedule.currentStationIndex) {
                            continue;
                        }
                        if (alightSchedule.arrivalMillis < bestArrival) {
                            bestArrival = alightSchedule.arrivalMillis;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            // スケジュールミリ秒データが見つからない/異常値の場合でも、同一路線（routeId）を共有する駅なら安全に降車を可能にするフォールバック
            if (bestArrival == Long.MAX_VALUE) {
                bestArrival = boardingSchedule.arrivalMillis > 0
                        ? boardingSchedule.arrivalMillis + 60000L * (alightIndex - passenger.routeTargetIndex)
                        : System.currentTimeMillis() + 60000L * (alightIndex - passenger.routeTargetIndex);
            }

            double alightX = Double.NaN;
            double alightY = Double.NaN;
            double alightZ = Double.NaN;
            BlockPos mid = resolvedAlightPlatform.getMidPos();
            if (mid != null) {
                alightX = mid.getX() + 0.5;
                alightY = mid.getY() + 1.0;
                alightZ = mid.getZ() + 0.5;
            }

            return new AlightPlan(resolvedAlightPlatformId, bestArrival, alightIndex, alightX, alightY, alightZ);
        }
        return null;
    }

    private static Field getCachedField(Class<?> clazz, String fieldName) {
        String cacheKey = clazz.getName() + "::" + fieldName;
        if (FIELD_CACHE.containsKey(cacheKey)) {
            return FIELD_CACHE.get(cacheKey);
        }
        try {
            Field field = clazz.getField(fieldName);
            field.setAccessible(true);
            FIELD_CACHE.put(cacheKey, field);
            return field;
        } catch (Throwable t) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(cacheKey, field);
                return field;
            } catch (Throwable t2) {
                if (clazz.getSuperclass() != null) {
                    Field field = getCachedField(clazz.getSuperclass(), fieldName);
                    if (field != null) {
                        FIELD_CACHE.put(cacheKey, field);
                        return field;
                    }
                }
            }
        }
        FIELD_CACHE.put(cacheKey, null);
        return null;
    }

    private static BlockPos tryReadBlockPos(Object target, String fieldName) {
        if (target == null) return null;
        Field field = getCachedField(target.getClass(), fieldName);
        if (field != null) {
            try {
                return (BlockPos) field.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Vec3d getCachedCarPosition(TrainServer train, int carIndex) {
        String key = train.id + "_" + carIndex;
        if (TRAIN_CAR_POS_CACHE.containsKey(key)) {
            return TRAIN_CAR_POS_CACHE.get(key);
        }
        Vec3d carPos = null;
        try {
            carPos = ((TrainAccessor) train).callGetRoutePosition(carIndex, train.spacing);
        } catch (Throwable ignored) {}

        TRAIN_CAR_POS_CACHE.put(key, carPos);
        return carPos;
    }

    private static boolean isTrainAtPlatform(Train train, Platform platform) {
        BlockPos p1 = tryReadBlockPos(platform, "pos1");
        BlockPos p2 = tryReadBlockPos(platform, "pos2");

        if (p1 == null || p2 == null) {
            BlockPos mid = platform.getMidPos();
            if (mid == null) {
                return false;
            }
            Vec3d center = new Vec3d(mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5);
            int cars = Math.max(1, train.trainCars);
            for (int carIndex = 0; carIndex < cars; carIndex++) {
                Vec3d carPos = getCachedCarPosition((TrainServer) train, carIndex);
                if (carPos != null && carPos.squaredDistanceTo(center) <= TRAIN_PLATFORM_MATCH_DISTANCE_SQ) {
                    return true;
                }
            }
            return false;
        }

        double minX = Math.min(p1.getX(), p2.getX());
        double maxX = Math.max(p1.getX(), p2.getX()) + 1.0;
        double minY = Math.min(p1.getY(), p2.getY());
        double maxY = Math.max(p1.getY(), p2.getY()) + 1.0;
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1.0;

        int cars = Math.max(1, train.trainCars);
        for (int carIndex = 0; carIndex < cars; carIndex++) {
            Vec3d carPos = getCachedCarPosition((TrainServer) train, carIndex);
            if (carPos == null) continue;

            double closestX = Math.max(minX, Math.min(carPos.x, maxX));
            double closestY = Math.max(minY, Math.min(carPos.y, maxY));
            double closestZ = Math.max(minZ, Math.min(carPos.z, maxZ));

            double dx = carPos.x - closestX;
            double dy = carPos.y - closestY;
            double dz = carPos.z - closestZ;

            double horizontalDistSq = dx * dx + dz * dz;
            double verticalDist = Math.abs(dy);

            if (horizontalDistSq <= TRAIN_PLATFORM_MATCH_DISTANCE_SQ && verticalDist <= 3.0) { // 許容高さを2.0mから3.0mに拡大
                return true;
            }
        }
        return false;
    }

    private static TrainServer findBoardableTrain(ServerWorld world, RailwayData railwayData, long platformId, long routeId, Vec3d waitingPos) {
        String cacheKey = platformId + "_" + routeId;
        if (BOARDABLE_TRAIN_CACHE.containsKey(cacheKey)) {
            return BOARDABLE_TRAIN_CACHE.get(cacheKey);
        }
        TrainServer result = findBoardableTrainUncached(world, railwayData, platformId, routeId, waitingPos);
        BOARDABLE_TRAIN_CACHE.put(cacheKey, result);
        return result;
    }

    private static TrainServer findBoardableTrainUncached(ServerWorld world, RailwayData railwayData, long platformId, long routeId, Vec3d waitingPos) {
        Platform platform = railwayData.dataCache.platformIdMap.get(platformId);
        if (platform == null) {
            return null;
        }
        for (Siding siding : railwayData.sidings) {
            for (TrainServer train : getSidingTrains(siding)) {
                if (!trainServesRoute(railwayData, train, routeId)) {
                    continue;
                }
                // 電車ドア判定: ドアが閉まっている途中でも僅かでも開いている(0.0F超)か、MTR側のドアオープン中に引っかかるように調整
                if (train.getDoorValue() <= 0.0F || Math.abs(train.getSpeed()) > 0.05F) {
                    continue;
                }
                if (isTrainAtPlatform(train, platform)) {
                    return train;
                }
            }
        }
        return null;
    }

    private static boolean isTrainReadyForAlight(ServerWorld world, RailwayData railwayData, Passenger passenger) {
        if (passenger.currentTrainId == null) {
            return false;
        }
        for (Siding siding : railwayData.sidings) {
            for (TrainServer train : getSidingTrains(siding)) {
                if (train.id != passenger.currentTrainId) {
                    continue;
                }
                if (train.getDoorValue() <= 0.0F || Math.abs(train.getSpeed()) > 0.05F) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<TrainServer> getSidingTrains(Siding siding) {
        try {
            Field trainsField = getCachedField(Siding.class, "trains");
            if (trainsField != null) {
                Object value = trainsField.get(siding);
                if (value instanceof Set<?>) {
                    return (Set<TrainServer>) value;
                }
            }
        } catch (Throwable ignored) {}
        return Set.of();
    }

    private static boolean trainServesRoute(RailwayData railwayData, TrainServer train, long routeId) {
        try {
            Field routeIdField = getCachedField(TrainServer.class, "routeId");
            if (routeIdField != null) {
                Object value = routeIdField.get(train);
                if (value instanceof Number number) {
                    return number.longValue() == routeId;
                }
            }
        } catch (Throwable ignored) {}

        var depot = railwayData.dataCache.sidingIdToDepot.get(train.sidingId);
        return depot != null && depot.routeIds.contains(routeId);
    }

    private static void walkTowards(ServerWorld world, Passenger passenger, BlockPos targetPosBlock, double walkSpeed) {
        ensureWalkPath(world, passenger, targetPosBlock);
        Vec3d currentPos = new Vec3d(passenger.x, passenger.y, passenger.z);

        if (!passenger.walkPath.isEmpty() && passenger.walkPathIndex < passenger.walkPath.size()) {
            BlockPos nextNode = BlockPos.fromLong(passenger.walkPath.get(passenger.walkPathIndex));
            Vec3d nextPos = new Vec3d(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);

            double dxNode = currentPos.x - nextPos.x;
            double dzNode = currentPos.z - nextPos.z;
            if (dxNode * dxNode + dzNode * dzNode < 0.16) {
                passenger.walkPathIndex++;
                if (passenger.walkPathIndex < passenger.walkPath.size()) {
                    nextNode = BlockPos.fromLong(passenger.walkPath.get(passenger.walkPathIndex));
                    nextPos = new Vec3d(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);
                }
            }

            if (passenger.walkPathIndex < passenger.walkPath.size()) {
                Vec3d direction = nextPos.subtract(currentPos);
                if (direction.lengthSquared() > 0.0001) {
                    Vec3d laneTarget = getWalkingLaneTarget(world, passenger, currentPos, nextPos);
                    Vec3d movement = laneTarget.subtract(currentPos).normalize().multiply(walkSpeed);
                    applyMovement(world, passenger, movement);
                    return;
                }
            }
        }

        Vec3d fallbackTarget = new Vec3d(targetPosBlock.getX() + 0.5, targetPosBlock.getY(), targetPosBlock.getZ() + 0.5);
        Vec3d fallbackDirection = fallbackTarget.subtract(currentPos);
        if (fallbackDirection.lengthSquared() > 0.0001) {
            Vec3d laneTarget = getWalkingLaneTarget(world, passenger, currentPos, fallbackTarget);
            Vec3d movement = laneTarget.subtract(currentPos).normalize().multiply(walkSpeed);
            applyMovement(world, passenger, movement);
        }
    }

    private static void applyMovement(ServerWorld world, Passenger passenger, Vec3d movement) {
        Vec3d allowed = sanitizeMovement(world, new Vec3d(passenger.x, passenger.y, passenger.z), movement);
        passenger.x += allowed.x;
        passenger.y += allowed.y;
        passenger.z += allowed.z;
    }

    private static void ensureWalkPath(ServerWorld world, Passenger passenger, BlockPos targetPosBlock) {
        long targetKey = targetPosBlock.asLong();

        if (passenger.walkTargetKey == targetKey && !passenger.walkPath.isEmpty() && passenger.walkPathIndex < passenger.walkPath.size()) {
            return;
        }

        long currentTick = world.getTime();

        if (passenger.isPathfinding) {
            if (passenger.lastPathfindTick > 0 && (currentTick - passenger.lastPathfindTick) > 120) {
                LOGGER.warn("[RCAP] Pathfinding timeout detected for passenger {}. Resetting lock flag.", passenger.id);
                passenger.isPathfinding = false;
            } else {
                return;
            }
        }

        if (passenger.walkTargetKey == targetKey && passenger.lastPathfindTick > 0 && (currentTick - passenger.lastPathfindTick) < 40) {
            return;
        }

        CachedPath cachedResult = PATH_CACHE_BY_TARGET.get(targetKey);
        if (cachedResult != null && cachedResult.path != null && !cachedResult.path.isEmpty()) {
            passenger.walkPath.clear();
            passenger.walkPath.addAll(cachedResult.path);
            passenger.walkPathIndex = 0;
            passenger.walkTargetKey = targetKey;
            passenger.lastPathfindTick = currentTick;
            LOGGER.debug("[RCAP] Goal Path cache HIT for target block: {}", targetPosBlock);
            return;
        }

        BlockPos start = new BlockPos(passenger.x, passenger.y, passenger.z);

        long chunkKey = ChunkPos.toLong(start.getX() >> 4, start.getZ() >> 4);
        ThreadSafeRegionView snapshot = REGION_VIEW_CACHE.get(chunkKey);

        if (snapshot == null) {
            if (newSnapshotsGeneratedThisTick >= MAX_NEW_SNAPSHOTS_PER_TICK) {
                return;
            }
            snapshot = new ThreadSafeRegionView(world, start, WALK_PATH_MAX_DISTANCE);
            REGION_VIEW_CACHE.put(chunkKey, snapshot);
            newSnapshotsGeneratedThisTick++;
        }

        final ThreadSafeRegionView finalSnapshot = snapshot;
        passenger.lastPathfindTick = currentTick;
        passenger.isPathfinding = true;

        CompletableFuture.supplyAsync(() -> {
            try {
                return PassengerPathfinder.findPath(finalSnapshot, start, targetPosBlock, WALK_PATH_MAX_DISTANCE);
            } catch (Throwable t) {
                LOGGER.error("[RCAP] 非同期経路探索スレッドで例外が発生しました: ", t);
                return List.<BlockPos>of(targetPosBlock);
            }
        }, PATHFIND_EXECUTOR).thenAcceptAsync(path -> {
            if (path != null && !path.isEmpty()) {
                List<Long> pathLongs = path.stream().map(BlockPos::asLong).collect(Collectors.toList());
                passenger.walkPath.clear();
                passenger.walkPath.addAll(pathLongs);
                passenger.walkPathIndex = 0;
                passenger.walkTargetKey = targetKey;
                PATH_CACHE_BY_TARGET.put(targetKey, new CachedPath(pathLongs, System.currentTimeMillis()));
            }
            passenger.isPathfinding = false;
        }, world.getServer()::execute);
    }

    private static void clearWalkPath(Passenger passenger) {
        passenger.walkPath.clear();
        passenger.walkPathIndex = 0;
        passenger.walkTargetKey = Long.MIN_VALUE;
        passenger.isPathfinding = false;
    }

    private static BlockPos resolveStandingPos(ServerWorld world, BlockPos targetPos) {
        if (isStandingPosWalkable(world, targetPos)) {
            return targetPos;
        }
        BlockPos up = targetPos.up();
        if (isStandingPosWalkable(world, up)) {
            return up;
        }
        BlockPos down = targetPos.down();
        if (isStandingPosWalkable(world, down)) {
            return down;
        }
        return up;
    }

    private static boolean isStandingPosWalkable(ServerWorld world, BlockPos pos) {
        double px = pos.getX() + 0.5;
        double py = pos.getY();
        double pz = pos.getZ() + 0.5;
        return isSpaceClearAt(world, px, py, pz) &&
                !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
    }

    private record AlightPlan(long platformId, long alightTimeMillis, int routeIndex, double alightX, double alightY, double alightZ) {
    }

    private static Vec3d getWalkingLaneTarget(ServerWorld world, Passenger passenger, Vec3d currentPos, Vec3d targetPos) {
        Vec3d forward = targetPos.subtract(currentPos);
        double horizontalLengthSq = forward.x * forward.x + forward.z * forward.z;
        if (horizontalLengthSq < 0.0001) {
            return targetPos;
        }
        double horizontalLength = Math.sqrt(horizontalLengthSq);
        Vec3d right = new Vec3d(-forward.z / horizontalLength, 0, forward.x / horizontalLength);
        long laneSeed = passenger.id ^ passenger.routeTargetIndex ^ getExpectedBoardingRouteId(passenger, passenger.boardingRouteIds);
        int preferredLaneIndex = Math.floorMod(Long.hashCode(laneSeed), WALKING_LANES.length);

        for (int attempt = 0; attempt < WALKING_LANES.length; attempt++) {
            int laneIndex = (preferredLaneIndex + attempt) % WALKING_LANES.length;
            double laneOffset = WALKING_LANES[laneIndex];
            Vec3d laneTarget = targetPos.add(right.multiply(laneOffset));
            if (isSafeStandingPos(world, laneTarget)) {
                return laneTarget;
            }
        }
        return targetPos;
    }

    private static boolean isSafeStandingPos(ServerWorld world, Vec3d pos) {
        BlockPos feet = new BlockPos(pos.x, pos.y, pos.z);
        BlockPos head = feet.up();
        BlockPos ground = feet.down();
        if (world.getBlockState(ground).getCollisionShape(world, ground).isEmpty()) {
            return false;
        }
        if (world.getBlockState(ground).isIn(BlockTags.RAILS)) {
            return false;
        }
        if (world.getBlockState(feet).isIn(BlockTags.RAILS) || world.getBlockState(head).isIn(BlockTags.RAILS)) {
            return false;
        }
        return isSpaceClearAt(world, pos.x, pos.y, pos.z);
    }

    private static double findWalkableY(ServerWorld world, double x, double currentY, double z) {
        double bestY = Double.NaN;
        double bestDist = Double.MAX_VALUE;

        int baseBlockY = (int) Math.floor(currentY);
        for (int dy = -2; dy <= 2; dy++) {
            BlockPos checkPos = new BlockPos(x, baseBlockY + dy, z);
            BlockState state = world.getBlockState(checkPos);

            double maxCollisionY = 0.0;
            if (!state.getCollisionShape(world, checkPos).isEmpty()) {
                maxCollisionY = state.getCollisionShape(world, checkPos).getMax(net.minecraft.util.math.Direction.Axis.Y);
                if (Double.isNaN(maxCollisionY)) {
                    maxCollisionY = 1.0;
                }
            } else {
                continue;
            }

            double surfaceY = checkPos.getY() + maxCollisionY;
            double dist = Math.abs(surfaceY - currentY);

            if (dist > 1.25) {
                continue;
            }

            if (dist < bestDist && isSpaceClearAt(world, x, surfaceY, z)) {
                bestY = surfaceY;
                bestDist = dist;
            }
        }
        return bestY;
    }

    private static boolean isBuried(ServerWorld world, Vec3d pos) {
        BlockPos feet = new BlockPos(pos.x, pos.y, pos.z);
        return !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() &&
                isSpaceClearAt(world, pos.x, pos.y, pos.z);
    }

    private static Vec3d sanitizeMovement(ServerWorld world, Vec3d currentPos, Vec3d movement) {
        if (movement.lengthSquared() < 0.000001) {
            return Vec3d.ZERO;
        }

        double moveX = movement.x;
        double moveZ = movement.z;

        double[] scales = {1.0, 0.5, 0.25, 0.0};
        for (double scale : scales) {
            if (scale == 0.0) break;

            double candX = moveX * scale;
            double candZ = moveZ * scale;

            double targetX = currentPos.x + candX;
            double targetZ = currentPos.z + candZ;

            double targetY = findWalkableY(world, targetX, currentPos.y, targetZ);

            if (!Double.isNaN(targetY)) {
                Vec3d candidatePos = new Vec3d(targetX, targetY, targetZ);
                if (isSpaceClearAt(world, targetX, targetY, targetZ)) {
                    return candidatePos.subtract(currentPos);
                }
            }
        }

        if (isBuried(world, currentPos)) {
            for (double dy = 0.1; dy <= 1.1; dy += 0.2) {
                Vec3d popPos = currentPos.add(0, dy, 0);
                if (isSpaceClearAt(world, popPos.x, popPos.y, popPos.z)) {
                    return new Vec3d(0, dy, 0);
                }
            }
        }

        return Vec3d.ZERO;
    }

    private static TrainServer getTrainStoppedAtPlatform(RailwayData railwayData, Platform platform) {
        if (platform == null) return null;
        long platformId = platform.id;
        if (STOPPED_TRAIN_CACHE.containsKey(platformId)) {
            return STOPPED_TRAIN_CACHE.get(platformId);
        }
        TrainServer result = getTrainStoppedAtPlatformUncached(railwayData, platform);
        STOPPED_TRAIN_CACHE.put(platformId, result);
        return result;
    }

    private static TrainServer getTrainStoppedAtPlatformUncached(RailwayData railwayData, Platform platform) {
        if (platform == null || railwayData == null) {
            return null;
        }
        for (Siding siding : railwayData.sidings) {
            for (TrainServer train : getSidingTrains(siding)) {
                if (train.getDoorValue() > 0.0F || Math.abs(train.getSpeed()) <= 0.05F) {
                    if (isTrainAtPlatform(train, platform)) {
                        return train;
                    }
                }
            }
        }
        return null;
    }

    private static int getClosestCarIndex(Train train, Vec3d pos) {
        int carCount = Math.max(1, getTrainCarCount(train));
        double bestDistance = Double.MAX_VALUE;
        int bestCarIndex = 0;
        for (int carIndex = 0; carIndex < carCount; carIndex++) {
            Vec3d carPos = getCachedCarPosition((TrainServer) train, carIndex);
            if (carPos != null) {
                double distance = carPos.squaredDistanceTo(pos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestCarIndex = carIndex;
                }
            }
        }
        return bestCarIndex;
    }

    private static int getTrainCarCount(Train train) {
        try {
            Field field = getCachedField(train.getClass(), "trainCars");
            if (field != null) {
                Object value = field.get(train);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            }
        } catch (Throwable ignored) {}

        try {
            // フィールドキャッシュを介した安全な解決
            Method method = train.getClass().getMethod("getTrainCars");
            Object value = method.invoke(train);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {}
        return 1;
    }

    private static long getExpectedBoardingRouteId(Passenger passenger, List<Long> boardingRouteIds) {
        if (boardingRouteIds == null || passenger.routeTargetIndex < 0 || passenger.routeTargetIndex >= boardingRouteIds.size()) {
            return -1L;
        }
        return boardingRouteIds.get(passenger.routeTargetIndex);
    }
}