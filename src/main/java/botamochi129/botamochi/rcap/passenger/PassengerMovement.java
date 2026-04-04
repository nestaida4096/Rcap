package botamochi129.botamochi.rcap.passenger;

import botamochi129.botamochi.rcap.data.RidingPosManager;
import botamochi129.botamochi.rcap.mixin.TrainAccessor;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.ScheduleEntry;
import mtr.data.Siding;
import mtr.data.Train;
import mtr.data.TrainServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PassengerMovement {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final long EVENING_RUSH_START_TICKS = 10_000L;
    private static final long EVENING_RUSH_END_TICKS = 16_000L;
    private static final int WALK_PATH_MAX_DISTANCE = 64;
    private static final double TRAIN_PLATFORM_MATCH_DISTANCE_SQ = 144.0;
    private static final double PASSENGER_SEPARATION_RADIUS = 0.9;
    private static final double PASSENGER_SEPARATION_RADIUS_SQ = PASSENGER_SEPARATION_RADIUS * PASSENGER_SEPARATION_RADIUS;
    private static final double PASSENGER_SEPARATION_STRENGTH = 0.04;
    private static final double PASSENGER_TARGET_PULL_STRENGTH = 0.04;
    private static final double PASSENGER_MAX_TARGET_DEVIATION = 1.35;
    private static final double[] WALKING_LANES = {-0.45, -0.25, 0.0, 0.25, 0.45};

    public static void updatePassenger(ServerWorld world, Passenger passenger) {
        LOGGER.debug("[Passenger] {} updatePassenger called. world={}", passenger.name, world.getRegistryKey().getValue());
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
            long ridingPosSeed = passenger.id ^ targetPlatformId ^ expectedRouteId ^ ((long) passenger.routeTargetIndex << 32);
            int index = Math.floorMod(Long.hashCode(ridingPosSeed), ridingPositions.size());
            targetPosBlock = ridingPositions.get(index).getPos();
        } else {
            targetPosBlock = platform.getMidPos();
        }
        BlockPos standingTargetPos = resolveStandingPos(world, targetPosBlock);
        Vec3d targetPos = new Vec3d(standingTargetPos.getX() + 0.5, standingTargetPos.getY(), standingTargetPos.getZ() + 0.5);
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
                    if (advanceIfSameStationTransfer(railwayData, passenger, targetPlatformId)) {
                        clearWalkPath(passenger);
                        LOGGER.debug("[Passenger] {} walking transfer within station from platform {} to index {}", passenger.id, targetPlatformId, passenger.routeTargetIndex);
                        return;
                    }
                    passenger.moveState = Passenger.MoveState.WAITING_FOR_TRAIN;
                    clearWalkPath(passenger);
                    applySeparationWhileWaiting(world, passenger, targetPos);
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
                long now = System.currentTimeMillis();
                List<ScheduleEntry> schedules = railwayData.getSchedulesAtPlatform(targetPlatformId);
                ScheduleEntry matched = null;
                AlightPlan alightPlan = null;

                if (schedules != null) {
                    for (ScheduleEntry s : schedules) {
                        try {
                            if (s.arrivalMillis <= now) {
                                if (expectedRouteId > 0 && s.routeId != expectedRouteId) {
                                    continue;
                                }
                                AlightPlan candidatePlan = findAlightPlan(railwayData, passenger, s);
                                TrainServer boardableTrain = candidatePlan == null ? null : findBoardableTrain(world, railwayData, targetPlatformId, s.routeId, targetPos);
                                if (candidatePlan != null && boardableTrain != null) {
                                    matched = s;
                                    alightPlan = candidatePlan;
                                    passenger.currentTrainId = boardableTrain.id;
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            // arrivalMillis が想定外ならスキップ
                        }
                    }
                }

                if (matched != null && alightPlan != null) {
                    // 乗車：matched の routeId と arrivalMillis を利用して降車時刻を推定する
                    passenger.moveState = Passenger.MoveState.ON_TRAIN;
                    passenger.boardingTimeMillis = matched.arrivalMillis > 0 ? matched.arrivalMillis : now;
                    passenger.boardingPlatformId = targetPlatformId;
                    passenger.scheduledRouteId = matched.routeId;

                    // boarding 座標（サーバで計算）: targetPos
                    passenger.boardingX = targetPos.x;
                    passenger.boardingY = targetPos.y;
                    passenger.boardingZ = targetPos.z;

                    passenger.alightTimeMillis = alightPlan.alightTimeMillis;
                    passenger.alightingPlatformId = alightPlan.platformId;
                    passenger.alightX = alightPlan.alightX;
                    passenger.alightY = alightPlan.alightY;
                    passenger.alightZ = alightPlan.alightZ;
                    passenger.alightRouteIndex = alightPlan.routeIndex;

                    LOGGER.info("[Passenger] {} boarded: routeId={}, boardingPlatform={}, boardingTime={}, alightPlatform={}, alightTime={}",
                            passenger.id, passenger.scheduledRouteId, passenger.boardingPlatformId, passenger.boardingTimeMillis, passenger.alightingPlatformId, passenger.alightTimeMillis);
                } else {
                    applySeparationWhileWaiting(world, passenger, targetPos);
                }
                break;

            case ON_TRAIN:
                long cur = System.currentTimeMillis();
                if (passenger.alightTimeMillis > 0 && cur >= passenger.alightTimeMillis && isTrainReadyForAlight(world, railwayData, passenger)) {
                    // 降車処理
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
                        // リセット（次の待機/乗車で再計算）
                        passenger.currentTrainId = null;
                        passenger.boardingTimeMillis = -1L;
                        passenger.alightTimeMillis = -1L;
                        passenger.boardingPlatformId = -1L;
                        passenger.alightingPlatformId = -1L;
                        passenger.scheduledRouteId = -1L;
                        passenger.alightRouteIndex = -1;
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
                        passenger.lastAlightedRouteId = passenger.scheduledRouteId;
                        passenger.lastAlightedAtMillis = cur;
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
                    walkTowards(world, passenger, resolveStandingPos(world, new BlockPos(dest)), walkSpeed);
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
                    passenger.currentTrainId = null;
                    passenger.currentCarIndex = -1;
                    passenger.boardingTimeMillis = -1L;
                    passenger.alightTimeMillis = -1L;
                    passenger.boardingPlatformId = -1L;
                    passenger.alightingPlatformId = -1L;
                    passenger.scheduledRouteId = -1L;
                    passenger.alightRouteIndex = -1;
                    passenger.boardingX = passenger.boardingY = passenger.boardingZ = Double.NaN;
                    passenger.alightX = passenger.alightY = passenger.alightZ = Double.NaN;
                    LOGGER.info("[Passenger] {} starting return trip", passenger.id);
                } else {
                    applySeparationWhileWaiting(world, passenger, new Vec3d(passenger.x, passenger.y, passenger.z));
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

    private static boolean advanceIfSameStationTransfer(RailwayData railwayData, Passenger passenger, long currentPlatformId) {
        int nextIndex = passenger.routeTargetIndex + 1;
        if (nextIndex >= passenger.route.size()) {
            return false;
        }
        long nextPlatformId = passenger.route.get(nextIndex);
        if (!isWalkTransfer(railwayData, currentPlatformId, nextPlatformId)) {
            return false;
        }
        passenger.routeTargetIndex = nextIndex;
        passenger.moveState = currentPlatformId == nextPlatformId ? Passenger.MoveState.WAITING_FOR_TRAIN : Passenger.MoveState.WALKING_TO_PLATFORM;
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
        for (int alightIndex = passenger.routeTargetIndex + 1; alightIndex < passenger.route.size(); alightIndex++) {
            long alightPlatformId = passenger.route.get(alightIndex);
            List<ScheduleEntry> alightSchedules = railwayData.getSchedulesAtPlatform(alightPlatformId);
            if (alightSchedules == null || alightSchedules.isEmpty()) {
                continue;
            }

            long bestArrival = Long.MAX_VALUE;
            for (ScheduleEntry alightSchedule : alightSchedules) {
                try {
                    if (alightSchedule.routeId != boardingSchedule.routeId) {
                        continue;
                    }
                    if (alightSchedule.arrivalMillis <= boardingSchedule.arrivalMillis) {
                        continue;
                    }
                    if (alightSchedule.currentStationIndex <= boardingSchedule.currentStationIndex) {
                        continue;
                    }
                    if (alightSchedule.arrivalMillis < bestArrival) {
                        bestArrival = alightSchedule.arrivalMillis;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (bestArrival == Long.MAX_VALUE) {
                continue;
            }

            Platform alightPlatform = railwayData.dataCache.platformIdMap.get(alightPlatformId);
            double alightX = Double.NaN;
            double alightY = Double.NaN;
            double alightZ = Double.NaN;
            if (alightPlatform != null) {
                BlockPos mid = alightPlatform.getMidPos();
                alightX = mid.getX() + 0.5;
                alightY = mid.getY() + 1.0;
                alightZ = mid.getZ() + 0.5;
            }

            return new AlightPlan(alightPlatformId, bestArrival, alightIndex, alightX, alightY, alightZ);
        }
        return null;
    }

    private static long getExpectedBoardingRouteId(Passenger passenger, List<Long> boardingRouteIds) {
        if (boardingRouteIds == null || passenger.routeTargetIndex < 0 || passenger.routeTargetIndex >= boardingRouteIds.size()) {
            return -1L;
        }
        return boardingRouteIds.get(passenger.routeTargetIndex);
    }

    private static TrainServer findBoardableTrain(ServerWorld world, RailwayData railwayData, long platformId, long routeId, Vec3d waitingPos) {
        Platform platform = railwayData.dataCache.platformIdMap.get(platformId);
        if (platform == null) {
            return null;
        }
        Vec3d platformCenter = new Vec3d(platform.getMidPos().getX() + 0.5, platform.getMidPos().getY() + 1.0, platform.getMidPos().getZ() + 0.5);
        Vec3d boardingTarget = waitingPos == null ? platformCenter : waitingPos;
        for (Siding siding : railwayData.sidings) {
            for (TrainServer train : getSidingTrains(siding)) {
                if (!trainServesRoute(railwayData, train, routeId)) {
                    continue;
                }
                if (train.getDoorValue() <= 0.0F || Math.abs(train.getSpeed()) > 0.05F) {
                    continue;
                }
                if (isTrainNearPosition(train, boardingTarget) || isTrainNearPosition(train, platformCenter)) {
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
            Field trainsField = Siding.class.getDeclaredField("trains");
            trainsField.setAccessible(true);
            Object value = trainsField.get(siding);
            if (value instanceof Set<?>) {
                return (Set<TrainServer>) value;
            }
        } catch (Throwable ignored) {
        }
        return Set.of();
    }

    private static boolean trainServesRoute(RailwayData railwayData, TrainServer train, long routeId) {
        try {
            Field routeIdField = TrainServer.class.getDeclaredField("routeId");
            routeIdField.setAccessible(true);
            Object value = routeIdField.get(train);
            if (value instanceof Number number) {
                return number.longValue() == routeId;
            }
        } catch (Throwable ignored) {
        }
        var depot = railwayData.dataCache.sidingIdToDepot.get(train.sidingId);
        return depot != null && depot.routeIds.contains(routeId);
    }

    private static boolean isTrainNearPosition(Train train, Vec3d position) {
        int cars = Math.max(1, train.trainCars);
        for (int carIndex = 0; carIndex < cars; carIndex++) {
            try {
                Vec3d carPos = ((TrainAccessor) train).callGetRoutePosition(carIndex, train.spacing);
                if (carPos.squaredDistanceTo(position) <= TRAIN_PLATFORM_MATCH_DISTANCE_SQ) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
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
                    Vec3d laneTarget = getWalkingLaneTarget(world, passenger, currentPos, nextPos);
                    Vec3d movement = laneTarget.subtract(currentPos).normalize().multiply(walkSpeed);
                    passenger.x += movement.x;
                    passenger.y += movement.y;
                    passenger.z += movement.z;
                    return;
                }
            }
        }

        Vec3d fallbackTarget = new Vec3d(targetPosBlock.getX() + 0.5, targetPosBlock.getY(), targetPosBlock.getZ() + 0.5);
        Vec3d fallbackDirection = fallbackTarget.subtract(currentPos);
        if (fallbackDirection.lengthSquared() > 0.0001) {
            Vec3d laneTarget = getWalkingLaneTarget(world, passenger, currentPos, fallbackTarget);
            Vec3d movement = laneTarget.subtract(currentPos).normalize().multiply(walkSpeed);
            passenger.x += movement.x;
            passenger.y += movement.y;
            passenger.z += movement.z;
        }
    }

    private static void applySeparationWhileWaiting(ServerWorld world, Passenger passenger, Vec3d targetPos) {
        Vec3d currentPos = new Vec3d(passenger.x, passenger.y, passenger.z);
        Vec3d movement = applySeparation(world, passenger, currentPos, targetPos, Vec3d.ZERO);
        passenger.x += movement.x;
        passenger.y += movement.y;
        passenger.z += movement.z;
    }

    private static Vec3d applySeparation(ServerWorld world, Passenger passenger, Vec3d currentPos, Vec3d targetPos, Vec3d baseMovement) {
        Vec3d separation = Vec3d.ZERO;
        for (Passenger other : PassengerManager.PASSENGER_LIST) {
            if (other == passenger) {
                continue;
            }
            if (!passenger.worldId.equals(other.worldId)) {
                continue;
            }
            if (other.moveState == Passenger.MoveState.ON_TRAIN || other.moveState == Passenger.MoveState.IDLE) {
                continue;
            }
            Vec3d otherPos = new Vec3d(other.x, other.y, other.z);
            Vec3d diff = currentPos.subtract(otherPos);
            double horizontalDistSq = diff.x * diff.x + diff.z * diff.z;
            if (horizontalDistSq < 0.0001 || horizontalDistSq > PASSENGER_SEPARATION_RADIUS_SQ) {
                continue;
            }
            double horizontalDist = Math.sqrt(horizontalDistSq);
            double strength = (PASSENGER_SEPARATION_RADIUS - horizontalDist) / PASSENGER_SEPARATION_RADIUS;
            separation = separation.add(diff.x / horizontalDist * strength, 0, diff.z / horizontalDist * strength);
        }

        Vec3d movement = baseMovement;
        if (separation.lengthSquared() > 0.0001) {
            movement = movement.add(separation.normalize().multiply(PASSENGER_SEPARATION_STRENGTH));
        }

        Vec3d predictedPos = currentPos.add(movement);
        Vec3d targetDelta = targetPos.subtract(predictedPos);
        double targetDistSq = targetDelta.x * targetDelta.x + targetDelta.z * targetDelta.z;
        if (targetDistSq > PASSENGER_MAX_TARGET_DEVIATION * PASSENGER_MAX_TARGET_DEVIATION) {
            double targetDist = Math.sqrt(targetDistSq);
            movement = movement.add(targetDelta.x / targetDist * PASSENGER_TARGET_PULL_STRENGTH, 0, targetDelta.z / targetDist * PASSENGER_TARGET_PULL_STRENGTH);
        }
        return sanitizeMovement(world, currentPos, movement);
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

    private static Vec3d sanitizeMovement(ServerWorld world, Vec3d currentPos, Vec3d movement) {
        if (movement.lengthSquared() < 0.000001) {
            return Vec3d.ZERO;
        }

        double[] scales = {1.0, 0.5, 0.25, 0.0};
        for (double scale : scales) {
            Vec3d candidate = scale == 0.0 ? Vec3d.ZERO : movement.multiply(scale);
            Vec3d nextPos = currentPos.add(candidate);
            if (isSafeStandingPos(world, nextPos)) {
                return candidate;
            }
        }
        return Vec3d.ZERO;
    }

    private static boolean isSafeStandingPos(ServerWorld world, Vec3d pos) {
        BlockPos feet = new BlockPos(pos.x, pos.y, pos.z);
        BlockPos head = feet.up();
        BlockPos ground = feet.down();
        if (!isStandingPosWalkable(world, feet)) {
            return false;
        }
        if (world.getBlockState(ground).isIn(BlockTags.RAILS)) {
            return false;
        }
        if (world.getBlockState(feet).isIn(BlockTags.RAILS) || world.getBlockState(head).isIn(BlockTags.RAILS)) {
            return false;
        }
        return true;
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
        BlockPos feet = pos;
        BlockPos head = pos.up();
        BlockPos ground = pos.down();
        return world.getBlockState(ground).isOpaqueFullCube(world, ground)
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    private record AlightPlan(long platformId, long alightTimeMillis, int routeIndex, double alightX, double alightY, double alightZ) {
    }
}
