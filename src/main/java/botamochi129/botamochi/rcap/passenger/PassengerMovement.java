package botamochi129.botamochi.rcap.passenger;

import botamochi129.botamochi.rcap.data.RidingPosManager;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class PassengerMovement {

    private static final Logger LOGGER = LogManager.getLogger();

    // フォールバック移動時間（ms）: スケジュールから降車時刻が推定できない場合
    private static final long DEFAULT_TRAVEL_TIME_MS = 20_000L;
    private static final long EVENING_RUSH_START_TICKS = 10_000L;
    private static final long EVENING_RUSH_END_TICKS = 16_000L;
    private static final int WALK_PATH_MAX_DISTANCE = 64;

    public static void updatePassenger(ServerWorld world, Passenger passenger) {
        LOGGER.debug("[Passenger] {} updatePassenger called. world={}", passenger.name, world.getRegistryKey().getValue());
        List<Long> route = passenger.route;

        if (route == null || route.isEmpty() || passenger.routeTargetIndex >= route.size()) {
            if (passenger.moveState != Passenger.MoveState.IDLE) {
                passenger.moveState = Passenger.MoveState.IDLE;
            }
            return;
        }

        long targetPlatformId = route.get(passenger.routeTargetIndex);

        RailwayData railwayData = RailwayData.getInstance(world);
        if (railwayData == null || railwayData.dataCache.platformIdMap.isEmpty()) {
            LOGGER.warn("[Passenger] RailwayData not ready for passenger {}", passenger.id);
            return;
        }

        if (!railwayData.dataCache.platformIdMap.containsKey(targetPlatformId)) {
            LOGGER.warn("[Passenger] targetPlatformId {} not found", targetPlatformId);
            passenger.moveState = Passenger.MoveState.IDLE;
            return;
        }
        Platform platform = railwayData.dataCache.platformIdMap.get(targetPlatformId);
        if (platform == null) {
            passenger.moveState = Passenger.MoveState.IDLE;
            return;
        }

        BlockPos targetPosBlock;
        var ridingPositions = RidingPosManager.getRidingPositions(targetPlatformId);
        if (ridingPositions != null && !ridingPositions.isEmpty()) {
            int index = Math.floorMod(Long.hashCode(passenger.id ^ targetPlatformId), ridingPositions.size());
            targetPosBlock = ridingPositions.get(index).getPos();
        } else {
            targetPosBlock = platform.getMidPos();
        }
        Vec3d targetPos = new Vec3d(targetPosBlock.getX() + 0.5, targetPosBlock.getY() + 1, targetPosBlock.getZ() + 0.5);
        Vec3d currentPos = new Vec3d(passenger.x, passenger.y, passenger.z);
        double distanceSq = currentPos.squaredDistanceTo(targetPos);

        final double walkSpeed = 0.25;

        switch (passenger.moveState) {
            case WALKING_TO_PLATFORM:
                // If this is the final route platform, on arrival switch to walking to office instead of immediate removal
                if (passenger.routeTargetIndex == route.size() - 1 && distanceSq < 0.25) {
                    LOGGER.info("[Passenger] {} reached final platform (index {}). Switching to WALKING_TO_DESTINATION.", passenger.id, passenger.routeTargetIndex);
                    // If destination coords are known, go walk there; otherwise remove as fallback
                    if (!Double.isNaN(passenger.destinationX) && !Double.isNaN(passenger.destinationY) && !Double.isNaN(passenger.destinationZ)) {
                        passenger.moveState = Passenger.MoveState.WALKING_TO_DESTINATION;
                        // keep destination fields as-is
                    } else {
                        LOGGER.info("[Passenger] {} destination unknown -> removing passenger", passenger.id);
                        PassengerManager.PASSENGER_LIST.remove(passenger);
                        PassengerManager.save();
                        MinecraftServer server = world.getServer();
                        if (server != null) PassengerManager.broadcastToAllPlayers(server);
                    }
                    return;
                }

                if (distanceSq < 0.25) {
                    passenger.moveState = Passenger.MoveState.WAITING_FOR_TRAIN;
                    clearWalkPath(passenger);
                    LOGGER.debug("[Passenger] {} reached platform {} -> WAITING", passenger.id, targetPlatformId);
                } else {
                    walkTowards(world, passenger, targetPosBlock, walkSpeed);
                }
                break;

            case WAITING_FOR_TRAIN:
                long now = System.currentTimeMillis();
                List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(targetPlatformId);
                ScheduleEntry matched = null;

                if (schedules != null) {
                    for (ScheduleEntry s : schedules) {
                        try {
                            if (s.arrivalMillis <= now) {
                                matched = s;
                                break;
                            }
                        } catch (Throwable t) {
                            // arrivalMillis が想定外ならスキップ
                        }
                    }
                }

                if (matched != null) {
                    // 乗車：matched の routeId と arrivalMillis を利用して降車時刻を推定する
                    passenger.moveState = Passenger.MoveState.ON_TRAIN;
                    passenger.boardingTimeMillis = matched.arrivalMillis > 0 ? matched.arrivalMillis : now;
                    passenger.boardingPlatformId = targetPlatformId;
                    passenger.scheduledRouteId = matched.routeId;

                    // boarding 座標（サーバで計算）: targetPos
                    passenger.boardingX = targetPos.x;
                    passenger.boardingY = targetPos.y;
                    passenger.boardingZ = targetPos.z;

                    // 次駅（降車）を決める
                    long estimatedAlightTime = -1L;
                    long alightPlatformId = -1L;
                    int alightIndex = passenger.routeTargetIndex + 1;
                    if (alightIndex < passenger.route.size()) {
                        alightPlatformId = passenger.route.get(alightIndex);
                        // alightPlatformId のスケジュールから同じ routeId, arrivalMillis > boarding を探す
                        List<ScheduleEntry> alightSchedules = railwayData.getSchedulesAtPlatform(alightPlatformId);
                        if (alightSchedules != null) {
                            long best = Long.MAX_VALUE;
                            Platform alightPlatform = railwayData.dataCache.platformIdMap.get(alightPlatformId);
                            Vec3d alightPos = null;
                            if (alightPlatform != null) {
                                BlockPos mid = alightPlatform.getMidPos();
                                alightPos = new Vec3d(mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5);
                            }
                            for (ScheduleEntry as : alightSchedules) {
                                try {
                                    if (as.routeId == matched.routeId && as.arrivalMillis > passenger.boardingTimeMillis) {
                                        if (as.arrivalMillis < best) best = as.arrivalMillis;
                                    }
                                } catch (Throwable t) {
                                    // ignore
                                }
                            }
                            if (best != Long.MAX_VALUE) estimatedAlightTime = best;
                            // alight 座標（あれば）を設定
                            if (alightPos != null) {
                                passenger.alightX = alightPos.x;
                                passenger.alightY = alightPos.y;
                                passenger.alightZ = alightPos.z;
                            }
                        }
                    }

                    if (estimatedAlightTime <= 0L) {
                        // フォールバック（固定時間）
                        estimatedAlightTime = passenger.boardingTimeMillis + DEFAULT_TRAVEL_TIME_MS;
                    }

                    passenger.alightTimeMillis = estimatedAlightTime;
                    passenger.alightingPlatformId = alightPlatformId;

                    LOGGER.info("[Passenger] {} boarded: routeId={}, boardingPlatform={}, boardingTime={}, alightPlatform={}, alightTime={}",
                            passenger.id, passenger.scheduledRouteId, passenger.boardingPlatformId, passenger.boardingTimeMillis, passenger.alightingPlatformId, passenger.alightTimeMillis);
                }
                break;

            case ON_TRAIN:
                long cur = System.currentTimeMillis();
                if (passenger.alightTimeMillis > 0 && cur >= passenger.alightTimeMillis) {
                    // 降車処理
                    if (passenger.routeTargetIndex + 1 < passenger.route.size()) {
                        passenger.routeTargetIndex++;
                        if (!Double.isNaN(passenger.alightX) && !Double.isNaN(passenger.alightY) && !Double.isNaN(passenger.alightZ)) {
                            passenger.x = passenger.alightX;
                            passenger.y = passenger.alightY;
                            passenger.z = passenger.alightZ;
                        }
                        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
                        clearWalkPath(passenger);
                        // リセット（次の待機/乗車で再計算）
                        passenger.currentTrainId = null;
                        passenger.boardingTimeMillis = -1L;
                        passenger.alightTimeMillis = -1L;
                        passenger.boardingPlatformId = -1L;
                        passenger.alightingPlatformId = -1L;
                        passenger.scheduledRouteId = -1L;
                        passenger.boardingX = passenger.boardingY = passenger.boardingZ = Double.NaN;
                        passenger.alightX = passenger.alightY = passenger.alightZ = Double.NaN;
                        LOGGER.debug("[Passenger] {} alighted and will WALK to next platform (index {}).", passenger.id, passenger.routeTargetIndex);
                    } else {
                        // 最終到着: ここではプラットフォームに到着した後 WALKING_TO_DESTINATION に遷移する処理を行う
                        LOGGER.info("[Passenger] {} alight at final platform -> switch to WALKING_TO_DESTINATION", passenger.id);
                        if (!Double.isNaN(passenger.alightX) && !Double.isNaN(passenger.alightY) && !Double.isNaN(passenger.alightZ)) {
                            passenger.x = passenger.alightX;
                            passenger.y = passenger.alightY;
                            passenger.z = passenger.alightZ;
                        }
                        if (!Double.isNaN(passenger.destinationX) && !Double.isNaN(passenger.destinationY) && !Double.isNaN(passenger.destinationZ)) {
                            passenger.moveState = Passenger.MoveState.WALKING_TO_DESTINATION;
                            clearWalkPath(passenger);
                            // Keep the destination fields
                        } else {
                            // destination unknown -> remove immediately
                            LOGGER.info("[Passenger] {} destination unknown at final alight -> removing", passenger.id);
                            PassengerManager.PASSENGER_LIST.remove(passenger);
                            PassengerManager.save();
                            MinecraftServer server = world.getServer();
                            if (server != null) PassengerManager.broadcastToAllPlayers(server);
                        }
                    }
                }
                break;

            case WALKING_TO_DESTINATION:
                // Walk towards passenger.destinationX/Y/Z
                if (Double.isNaN(passenger.destinationX) || Double.isNaN(passenger.destinationY) || Double.isNaN(passenger.destinationZ)) {
                    // No destination -> remove as fallback
                    LOGGER.info("[Passenger] {} walking to destination but destination unknown -> removing", passenger.id);
                    PassengerManager.PASSENGER_LIST.remove(passenger);
                    PassengerManager.save();
                    MinecraftServer server = world.getServer();
                    if (server != null) PassengerManager.broadcastToAllPlayers(server);
                    return;
                }
                Vec3d dest = new Vec3d(passenger.destinationX, passenger.destinationY, passenger.destinationZ);
                Vec3d curPos = new Vec3d(passenger.x, passenger.y, passenger.z);
                double dist2 = curPos.squaredDistanceTo(dest);
                if (dist2 < 0.25) {
                    if (passenger.returningHome) {
                        LOGGER.info("[Passenger] {} reached home -> removing", passenger.id);
                        PassengerManager.PASSENGER_LIST.remove(passenger);
                        PassengerManager.save();
                        MinecraftServer server = world.getServer();
                        if (server != null) PassengerManager.broadcastToAllPlayers(server);
                        return;
                    }

                    passenger.moveState = Passenger.MoveState.WAITING_AT_DESTINATION;
                    if (passenger.commuteTrip) {
                        passenger.destinationWaitUntilMillis = -1L;
                        LOGGER.info("[Passenger] {} reached office -> waiting for evening rush return trip", passenger.id);
                    } else {
                        LOGGER.info("[Passenger] {} reached daytime destination -> waiting before return trip", passenger.id);
                    }
                    return;
                } else {
                    walkTowards(world, passenger, new BlockPos(dest), walkSpeed);
                }
                break;

            case WAITING_AT_DESTINATION:
                boolean shouldReturn = passenger.commuteTrip ? isEveningRush(world) : passenger.destinationWaitUntilMillis > 0 && System.currentTimeMillis() >= passenger.destinationWaitUntilMillis;
                if (shouldReturn) {
                    if (passenger.returnRoute.isEmpty() || Double.isNaN(passenger.homeX) || Double.isNaN(passenger.homeY) || Double.isNaN(passenger.homeZ)) {
                        LOGGER.info("[Passenger] {} return trip unavailable -> removing", passenger.id);
                        PassengerManager.PASSENGER_LIST.remove(passenger);
                        PassengerManager.save();
                        MinecraftServer server = world.getServer();
                        if (server != null) PassengerManager.broadcastToAllPlayers(server);
                        return;
                    }

                    passenger.route = new java.util.ArrayList<>(passenger.returnRoute);
                    passenger.routeTargetIndex = 0;
                    passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
                    passenger.destinationX = passenger.homeX;
                    passenger.destinationY = passenger.homeY;
                    passenger.destinationZ = passenger.homeZ;
                    passenger.returningHome = true;
                    passenger.destinationWaitUntilMillis = -1L;
                    clearWalkPath(passenger);
                    passenger.currentTrainId = null;
                    passenger.currentCarIndex = -1;
                    passenger.boardingTimeMillis = -1L;
                    passenger.alightTimeMillis = -1L;
                    passenger.boardingPlatformId = -1L;
                    passenger.alightingPlatformId = -1L;
                    passenger.scheduledRouteId = -1L;
                    passenger.boardingX = passenger.boardingY = passenger.boardingZ = Double.NaN;
                    passenger.alightX = passenger.alightY = passenger.alightZ = Double.NaN;
                    LOGGER.info("[Passenger] {} starting return trip", passenger.id);
                }
                break;

            default:
                break;
        }
    }

    private static boolean isEveningRush(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % 24000L;
        return timeOfDay >= EVENING_RUSH_START_TICKS && timeOfDay <= EVENING_RUSH_END_TICKS;
    }

    private static void walkTowards(ServerWorld world, Passenger passenger, BlockPos targetPosBlock, double walkSpeed) {
        ensureWalkPath(world, passenger, targetPosBlock);
        Vec3d currentPos = new Vec3d(passenger.x, passenger.y, passenger.z);

        if (!passenger.walkPath.isEmpty() && passenger.walkPathIndex < passenger.walkPath.size()) {
            BlockPos nextNode = BlockPos.fromLong(passenger.walkPath.get(passenger.walkPathIndex));
            Vec3d nextPos = new Vec3d(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);
            if (currentPos.squaredDistanceTo(nextPos) < 0.2) {
                passenger.walkPathIndex++;
                if (passenger.walkPathIndex < passenger.walkPath.size()) {
                    nextNode = BlockPos.fromLong(passenger.walkPath.get(passenger.walkPathIndex));
                    nextPos = new Vec3d(nextNode.getX() + 0.5, nextNode.getY(), nextNode.getZ() + 0.5);
                }
            }

            if (passenger.walkPathIndex < passenger.walkPath.size()) {
                Vec3d direction = nextPos.subtract(currentPos);
                if (direction.lengthSquared() > 0.0001) {
                    Vec3d movement = direction.normalize().multiply(walkSpeed);
                    passenger.x += movement.x;
                    passenger.y += movement.y;
                    passenger.z += movement.z;
                    return;
                }
            }
        }

        Vec3d fallbackTarget = new Vec3d(targetPosBlock.getX() + 0.5, targetPosBlock.getY() + 1.0, targetPosBlock.getZ() + 0.5);
        Vec3d fallbackDirection = fallbackTarget.subtract(currentPos);
        if (fallbackDirection.lengthSquared() > 0.0001) {
            Vec3d movement = fallbackDirection.normalize().multiply(walkSpeed);
            passenger.x += movement.x;
            passenger.y += movement.y;
            passenger.z += movement.z;
        }
    }

    private static void ensureWalkPath(ServerWorld world, Passenger passenger, BlockPos targetPosBlock) {
        long targetKey = targetPosBlock.asLong();
        if (passenger.walkTargetKey == targetKey && !passenger.walkPath.isEmpty() && passenger.walkPathIndex < passenger.walkPath.size()) {
            return;
        }

        BlockPos start = new BlockPos(passenger.x, passenger.y, passenger.z);
        List<BlockPos> path = PassengerPathfinder.findPath(world, start, targetPosBlock, WALK_PATH_MAX_DISTANCE);
        passenger.walkPath.clear();
        passenger.walkPath.addAll(path.stream().map(BlockPos::asLong).collect(Collectors.toList()));
        passenger.walkPathIndex = 0;
        passenger.walkTargetKey = targetKey;
    }

    private static void clearWalkPath(Passenger passenger) {
        passenger.walkPath.clear();
        passenger.walkPathIndex = 0;
        passenger.walkTargetKey = Long.MIN_VALUE;
    }
}
