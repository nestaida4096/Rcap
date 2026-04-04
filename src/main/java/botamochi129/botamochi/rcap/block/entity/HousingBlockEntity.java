package botamochi129.botamochi.rcap.block.entity;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.data.HousingManager;
import botamochi129.botamochi.rcap.data.OfficeManager;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import botamochi129.botamochi.rcap.screen.HousingBlockScreenHandler;
import mtr.data.Platform;
import mtr.data.RailwayData;
import mtr.data.Route;
import mtr.data.Station;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class HousingBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {

    private int householdSize = 1;
    private int spawnedToday = 0;
    private int daytimeTripsToday = 0;
    private int lastSpawnDay = -1;

    private Long linkedOfficePosLong = null;
    private List<Long> cachedRoute = null;

    // ★追加★ 最終乗客生成Tick管理（負荷軽減）
    private long lastSpawnTick = -1L;

    private static final long MORNING_RUSH_START_TICKS = 0L;
    private static final long MORNING_RUSH_END_TICKS = 2000L;
    private static final long DAYTIME_DEMAND_START_TICKS = 2500L;
    private static final long DAYTIME_DEMAND_END_TICKS = 9000L;
    private static final long DAYTIME_DEMAND_CHECK_INTERVAL = 200L;
    private static final long DAYTIME_STAY_TIME_MS = 8_000L;

    public HousingBlockEntity(BlockPos pos, BlockState state) {
        super(Rcap.HOUSING_BLOCK_ENTITY, pos, state);
    }

    public int getHouseholdSize() {
        return householdSize;
    }

    public void setHouseholdSize(int size) {
        this.householdSize = size;
        markDirty();
    }

    public Long getLinkedOfficePosLong() {
        return linkedOfficePosLong;
    }

    public List<Long> getCachedRoute() {
        return cachedRoute;
    }

    public void setCachedRoute(List<Long> route) {
        this.cachedRoute = normalizeRoute(route);
        markDirty();
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("HouseholdSize", householdSize);

        if (linkedOfficePosLong != null) {
            nbt.putLong("LinkedOfficePos", linkedOfficePosLong);
        } else {
            nbt.remove("LinkedOfficePos");
        }

        if (cachedRoute != null && !cachedRoute.isEmpty()) {
            long[] routeArray = cachedRoute.stream().mapToLong(Long::longValue).toArray();
            nbt.putLongArray("CachedRoute", routeArray);
        } else {
            nbt.remove("CachedRoute");
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        householdSize = nbt.getInt("HouseholdSize");

        if (nbt.contains("LinkedOfficePos")) {
            linkedOfficePosLong = nbt.getLong("LinkedOfficePos");
        } else {
            linkedOfficePosLong = null;
        }

        if (nbt.contains("CachedRoute")) {
            long[] routeArray = nbt.getLongArray("CachedRoute");
            cachedRoute = new ArrayList<>(routeArray.length);
            for (long l : routeArray) {
                cachedRoute.add(l);
            }
            cachedRoute = normalizeRoute(cachedRoute);
        } else {
            cachedRoute = null;
        }
    }

    public void setLinkedOfficePosLong(Long posLong) {
        this.linkedOfficePosLong = posLong;
        markDirty();
        if (posLong == null) {
            this.cachedRoute = null;
            markDirty();
            return;
        }

        if (this.world instanceof ServerWorld serverWorld) {
            RailwayData railwayData = RailwayData.getInstance(serverWorld);
            long homePid = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, this.pos, 2000, 1000, 1000);
            Platform homePlatform = railwayData.dataCache.platformIdMap.get(homePid);
            BlockPos homePlatformPos = (homePlatform != null) ? homePlatform.getMidPos() : null;

            BlockPos officeBlockPos = BlockPos.fromLong(posLong);
            long officePid = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, officeBlockPos, 2000, 1000, 1000);
            Platform officePlatform = railwayData.dataCache.platformIdMap.get(officePid);
            BlockPos officePlatformPos = (officePlatform != null) ? officePlatform.getMidPos() : null;

            if (homePlatformPos == null || officePlatformPos == null) {
                this.cachedRoute = null;
                markDirty();
                return;
            }

            List<Long> fallbackRoute = buildPlatformConnectionRoute(railwayData, homePid, officePid);
            List<Long> stationRoute = buildStationConnectionRoute(railwayData, homePid, officePid);
            List<Long> scheduledRoute = buildRouteFromRoutes(railwayData, homePid, officePid);
            if (stationRoute.size() > fallbackRoute.size()) {
                fallbackRoute = stationRoute;
            }
            if (scheduledRoute.size() > fallbackRoute.size()) {
                fallbackRoute = scheduledRoute;
            }
            if (fallbackRoute.isEmpty()) {
                fallbackRoute.add(homePid);
                if (officePid != homePid) {
                    fallbackRoute.add(officePid);
                }
            }
            setCachedRoute(fallbackRoute);

            if (railwayData != null && railwayData.railwayDataRouteFinderModule != null) {
                railwayData.railwayDataRouteFinderModule.findRoute(
                        homePlatformPos, officePlatformPos, 60,
                        (routeFinderDataList, duration) -> {
                            if (routeFinderDataList == null || routeFinderDataList.isEmpty()) {
                                this.cachedRoute = null;
                                markDirty();
                                return;
                            }
                            List<Long> platformIdList = new ArrayList<>();
                            for (var data : routeFinderDataList) {
                                Long pid = railwayData.dataCache.blockPosToPlatformId.get(data.pos.asLong());
                                if (pid != null && pid != 0) {
                                    platformIdList.add(pid);
                                }
                            }
                            if (!platformIdList.isEmpty()) {
                                setCachedRoute(platformIdList);
                            } else {
                                this.cachedRoute = null;
                                markDirty();
                            }
                        }
                );
            }
        }
    }

    public boolean ensureLinkedOffice() {
        if (linkedOfficePosLong != null) {
            return true;
        }
        var office = OfficeManager.getRandomAvailableOffice();
        if (office == null) {
            return false;
        }
        setLinkedOfficePosLong(office.getPos().asLong());
        return linkedOfficePosLong != null;
    }

    public List<HousingBlockScreenHandler.OfficeOption> getOfficeOptions() {
        return OfficeManager.getAll().stream()
                .sorted((a, b) -> Long.compare(a.getPos().asLong(), b.getPos().asLong()))
                .map(office -> new HousingBlockScreenHandler.OfficeOption(office.getPos().asLong(), formatOfficeLabel(office.getPos())))
                .collect(Collectors.toList());
    }

    public void spawnPassengersIfTime(World world, long worldTime) {
        //System.out.println("[spawnPassengersIfTime] worldTime=" + worldTime + ", householdSize=" + householdSize + ", spawnedToday=" + spawnedToday + ", cachedRoute=" + cachedRoute);

        long currentDay = worldTime / 24000L;
        if (lastSpawnDay != currentDay) {
            spawnedToday = 0;
            daytimeTripsToday = 0;
            lastSpawnDay = (int) currentDay;
            //System.out.println("[spawnPassengersIfTime] new day detected. Reset spawnedToday");
        }

        if (linkedOfficePosLong == null && !ensureLinkedOffice()) {
            return;
        }

        if (cachedRoute == null || cachedRoute.isEmpty()) {
            //System.out.println("[spawnPassengersIfTime] cachedRoute is null or empty, skipping spawn");
            return;
        }

        long timeOfDay = worldTime % 24000L;

        if (isMorningRush(timeOfDay)) {
            int targetCommuteCount = getTargetMorningSpawnCount(timeOfDay);
            while (spawnedToday < targetCommuteCount) {
                spawnPassengerWithRoute(cachedRoute, this.pos, spawnedToday);
                spawnedToday++;
                markDirty();
            }
        }

        if (shouldSpawnDaytimeDemand(world, timeOfDay) && spawnDaytimePassenger(world, daytimeTripsToday)) {
            daytimeTripsToday++;
            lastSpawnTick = world.getTime();
            markDirty();
        }
    }

    private boolean isMorningRush(long timeOfDay) {
        return timeOfDay >= MORNING_RUSH_START_TICKS && timeOfDay <= MORNING_RUSH_END_TICKS;
    }

    private int getTargetMorningSpawnCount(long timeOfDay) {
        if (householdSize <= 0) {
            return 0;
        }
        double progress = (double) (timeOfDay - MORNING_RUSH_START_TICKS) / (double) Math.max(1L, MORNING_RUSH_END_TICKS - MORNING_RUSH_START_TICKS);
        progress = Math.max(0.0, Math.min(1.0, progress));
        return Math.min(householdSize, Math.max(0, (int) Math.ceil(householdSize * progress)));
    }

    private boolean shouldSpawnDaytimeDemand(World world, long timeOfDay) {
        if (!(world instanceof ServerWorld)) {
            return false;
        }
        if (timeOfDay < DAYTIME_DEMAND_START_TICKS || timeOfDay > DAYTIME_DEMAND_END_TICKS) {
            return false;
        }
        if (daytimeTripsToday >= Math.max(1, householdSize / 3)) {
            return false;
        }
        if (world.getTime() - lastSpawnTick < DAYTIME_DEMAND_CHECK_INTERVAL) {
            return false;
        }
        Random random = new Random(this.pos.asLong() ^ world.getTime() ^ householdSize);
        return random.nextDouble() < 0.08;
    }

    private boolean spawnDaytimePassenger(World world, int seq) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }
        RailwayData railwayData = RailwayData.getInstance(serverWorld);
        if (railwayData == null) {
            return false;
        }

        long homePid = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, this.pos, 2000, 1000, 1000);
        if (homePid == -1L || homePid == 0L) {
            return false;
        }

        List<Long> candidates = new ArrayList<>();
        var connections = railwayData.dataCache.platformConnections.get(homePid);
        if (connections != null) {
            connections.keySet().forEach(id -> {
                if (id != homePid && id != 0L) {
                    candidates.add(id);
                }
            });
        }

        if (candidates.isEmpty()) {
            return false;
        }

        Collections.shuffle(candidates, new Random(this.pos.asLong() ^ world.getTime() ^ seq));
        Long targetPid = candidates.get(0);
        Platform targetPlatform = railwayData.dataCache.platformIdMap.get(targetPid);
        if (targetPlatform == null) {
            return false;
        }

        List<Long> route = new ArrayList<>();
        route.add(homePid);
        route.add(targetPid);
        spawnPassengerWithRoute(
                route,
                this.pos,
                householdSize + seq,
                false,
                targetPlatform.getMidPos().getX() + 0.5,
                targetPlatform.getMidPos().getY() + 1.0,
                targetPlatform.getMidPos().getZ() + 0.5
        );
        return true;
    }

    private void spawnPassengerWithRoute(List<Long> platformIdList, BlockPos homePos, int seq) {
        spawnPassengerWithRoute(platformIdList, homePos, seq, true, Double.NaN, Double.NaN, Double.NaN);
    }

    private void spawnPassengerWithRoute(List<Long> platformIdList, BlockPos homePos, int seq, boolean commuteTrip, double destinationX, double destinationY, double destinationZ) {
        List<Long> normalizedRoute = normalizeRoute(platformIdList);
        RailwayData railwayData = this.world instanceof ServerWorld serverWorld ? RailwayData.getInstance(serverWorld) : null;
        List<Long> boardingRouteIds = railwayData == null ? buildUnknownBoardingRouteIds(normalizedRoute) : buildBoardingRouteIds(railwayData, normalizedRoute);
        String worldId = "minecraft:overworld";
        if (!normalizedRoute.isEmpty() && normalizedRoute.get(0) != -1L) {
            var world = this.getWorld();
            if (world instanceof ServerWorld serverWorld) {
                worldId = serverWorld.getRegistryKey().getValue().toString(); // ワールドID文字列
            }
        }

        double x = homePos.getX() + 0.5;
        double y = homePos.getY() + 1.0;  // 住宅の高さや高さ調整は適宜
        double z = homePos.getZ() + 0.5;

        Passenger passenger = new Passenger(System.currentTimeMillis(), "Passenger-" + seq, x, y, z, 0xFFFFFF, worldId);
        passenger.route = new ArrayList<>(normalizedRoute);
        passenger.boardingRouteIds.clear();
        passenger.boardingRouteIds.addAll(boardingRouteIds);
        passenger.routeTargetIndex = 0;
        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
        passenger.homeX = x;
        passenger.homeY = y;
        passenger.homeZ = z;
        passenger.returnRoute.clear();
        for (int i = normalizedRoute.size() - 1; i >= 0; i--) {
            passenger.returnRoute.add(normalizedRoute.get(i));
        }
        List<Long> normalizedReturnRoute = normalizeRoute(passenger.returnRoute);
        passenger.returnRoute.clear();
        passenger.returnRoute.addAll(normalizedReturnRoute);
        passenger.returnBoardingRouteIds.clear();
        passenger.returnBoardingRouteIds.addAll(railwayData == null ? buildUnknownBoardingRouteIds(passenger.returnRoute) : buildBoardingRouteIds(railwayData, passenger.returnRoute));
        passenger.returningHome = false;
        passenger.destinationWaitUntilMillis = commuteTrip ? -1L : System.currentTimeMillis() + DAYTIME_STAY_TIME_MS;
        passenger.commuteTrip = commuteTrip;

        if (commuteTrip && this.linkedOfficePosLong != null) {
            BlockPos officeBlockPos = BlockPos.fromLong(this.linkedOfficePosLong);
            passenger.destinationX = officeBlockPos.getX() + 0.5;
            passenger.destinationY = officeBlockPos.getY() + 1.0;
            passenger.destinationZ = officeBlockPos.getZ() + 0.5;
        } else {
            passenger.destinationX = destinationX;
            passenger.destinationY = destinationY;
            passenger.destinationZ = destinationZ;
        }

        // boarding/alight fields may be set later by PassengerMovement when they board a train
        synchronized (PassengerManager.PASSENGER_LIST) {
            PassengerManager.PASSENGER_LIST.add(passenger);
        }
        PassengerManager.save();
    }

    public static List<HousingBlockEntity> getAllHousingBlocks(MinecraftServer server) {
        List<HousingBlockEntity> result = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (BlockPos pos : BlockPos.iterate(
                    world.getBottomY(), 0, world.getBottomY(),
                    world.getTopY() - 1, 255, world.getTopY() - 1)) {
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof HousingBlockEntity hbe) {
                    result.add(hbe);
                }
            }
        }
        return result;
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("世帯人数設定");
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {
        return new HousingBlockScreenHandler(syncId, inventory, this.getPos(), this.householdSize, linkedOfficePosLong == null ? Long.MIN_VALUE : linkedOfficePosLong, getOfficeOptions());
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(this.getPos());
        buf.writeInt(this.householdSize);
        buf.writeLong(linkedOfficePosLong == null ? Long.MIN_VALUE : linkedOfficePosLong);
        List<HousingBlockScreenHandler.OfficeOption> options = getOfficeOptions();
        buf.writeVarInt(options.size());
        for (HousingBlockScreenHandler.OfficeOption option : options) {
            buf.writeLong(option.posLong());
            buf.writeString(option.label());
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, HousingBlockEntity blockEntity) {
        HousingManager.registerHousing(pos);
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world != null && !world.isClient) {
            HousingManager.unregisterHousing(pos);
        }
    }

    private static String formatOfficeLabel(BlockPos pos) {
        return "Office (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static List<Long> buildPlatformConnectionRoute(RailwayData railwayData, long startPid, long goalPid) {
        if (startPid == 0L || goalPid == 0L || startPid == -1L || goalPid == -1L) {
            return new ArrayList<>();
        }
        if (startPid == goalPid) {
            ArrayList<Long> single = new ArrayList<>();
            single.add(startPid);
            return single;
        }

        ArrayDeque<Long> queue = new ArrayDeque<>();
        Map<Long, Long> previous = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        queue.add(startPid);
        visited.add(startPid);

        while (!queue.isEmpty()) {
            long current = queue.removeFirst();
            var connections = railwayData.dataCache.platformConnections.get(current);
            if (connections == null) {
                continue;
            }
            for (Long nextPid : connections.keySet()) {
                if (nextPid == null || nextPid == 0L || visited.contains(nextPid)) {
                    continue;
                }
                visited.add(nextPid);
                previous.put(nextPid, current);
                if (nextPid == goalPid) {
                    return reconstructRoute(previous, startPid, goalPid);
                }
                queue.addLast(nextPid);
            }
        }

        return new ArrayList<>();
    }

    private static List<Long> reconstructRoute(Map<Long, Long> previous, long startPid, long goalPid) {
        ArrayList<Long> reversed = new ArrayList<>();
        Long current = goalPid;
        while (current != null) {
            reversed.add(current);
            if (current == startPid) {
                break;
            }
            current = previous.get(current);
        }
        if (reversed.isEmpty() || reversed.get(reversed.size() - 1) != startPid) {
            return new ArrayList<>();
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<Long> buildStationConnectionRoute(RailwayData railwayData, long startPid, long goalPid) {
        Station startStation = railwayData.dataCache.platformIdToStation.get(startPid);
        Station goalStation = railwayData.dataCache.platformIdToStation.get(goalPid);
        if (startStation == null || goalStation == null) {
            return new ArrayList<>();
        }
        if (startStation.id == goalStation.id) {
            ArrayList<Long> singleStationRoute = new ArrayList<>();
            singleStationRoute.add(startPid);
            if (goalPid != startPid) {
                singleStationRoute.add(goalPid);
            }
            return singleStationRoute;
        }

        ArrayDeque<Long> queue = new ArrayDeque<>();
        Map<Long, Long> previousStation = new HashMap<>();
        Set<Long> visited = new HashSet<>();
        Map<Long, Station> stationById = new HashMap<>();
        stationById.put(startStation.id, startStation);
        stationById.put(goalStation.id, goalStation);
        queue.add(startStation.id);
        visited.add(startStation.id);

        while (!queue.isEmpty()) {
            long currentStationId = queue.removeFirst();
            Station currentStation = stationById.get(currentStationId);
            if (currentStation == null) {
                continue;
            }
            var connectedStations = railwayData.dataCache.stationIdToConnectingStations.get(currentStation);
            if (connectedStations == null) {
                continue;
            }
            for (Station nextStation : connectedStations) {
                if (nextStation == null || visited.contains(nextStation.id)) {
                    continue;
                }
                stationById.put(nextStation.id, nextStation);
                visited.add(nextStation.id);
                previousStation.put(nextStation.id, currentStationId);
                if (nextStation.id == goalStation.id) {
                    return mapStationPathToPlatforms(railwayData, reconstructRoute(previousStation, startStation.id, goalStation.id), startPid, goalPid);
                }
                queue.addLast(nextStation.id);
            }
        }

        return new ArrayList<>();
    }

    private static List<Long> mapStationPathToPlatforms(RailwayData railwayData, List<Long> stationPath, long startPid, long goalPid) {
        if (stationPath.isEmpty()) {
            return new ArrayList<>();
        }

        ArrayList<Long> platformRoute = new ArrayList<>();
        platformRoute.add(startPid);

        for (int i = 1; i < stationPath.size() - 1; i++) {
            long stationId = stationPath.get(i);
            long platformId = findAnyPlatformForStation(railwayData, stationId);
            if (platformId != 0L && platformId != -1L && platformId != startPid) {
                platformRoute.add(platformId);
            }
        }

        if (goalPid != startPid) {
            platformRoute.add(goalPid);
        }
        return normalizeRoute(platformRoute);
    }

    private static long findAnyPlatformForStation(RailwayData railwayData, long stationId) {
        for (Map.Entry<Long, Station> entry : railwayData.dataCache.platformIdToStation.entrySet()) {
            Station station = entry.getValue();
            if (station != null && station.id == stationId) {
                return entry.getKey();
            }
        }
        return 0L;
    }

    private static List<Long> buildRouteFromRoutes(RailwayData railwayData, long startPid, long goalPid) {
        ArrayList<Long> bestRoute = new ArrayList<>();

        for (Route startRoute : railwayData.routes) {
            int startIndex = startRoute.getPlatformIdIndex(startPid);
            if (startIndex < 0) {
                continue;
            }

            int directGoalIndex = startRoute.getPlatformIdIndex(goalPid);
            if (directGoalIndex > startIndex) {
                ArrayList<Long> direct = new ArrayList<>();
                direct.add(startPid);
                direct.add(goalPid);
                if (direct.size() > bestRoute.size()) {
                    bestRoute = direct;
                }
            }

            for (Route goalRoute : railwayData.routes) {
                int goalIndex = goalRoute.getPlatformIdIndex(goalPid);
                if (goalIndex < 0) {
                    continue;
                }

                List<Long> candidate = buildTransferRouteFromRoutes(railwayData, startRoute, startIndex, goalRoute, goalIndex, startPid, goalPid);
                if (candidate.size() > bestRoute.size()) {
                    bestRoute = new ArrayList<>(candidate);
                }
            }
        }

        return normalizeRoute(bestRoute);
    }

    private static List<Long> buildTransferRouteFromRoutes(RailwayData railwayData, Route startRoute, int startIndex, Route goalRoute, int goalIndex, long startPid, long goalPid) {
        TransferCandidate best = null;

        for (int i = startIndex + 1; i < startRoute.platformIds.size(); i++) {
            long startTransferPid = startRoute.platformIds.get(i).platformId;
            Station startTransferStation = railwayData.dataCache.platformIdToStation.get(startTransferPid);
            if (startTransferStation == null) {
                continue;
            }

            for (int j = 0; j < goalRoute.platformIds.size(); j++) {
                long goalTransferPid = goalRoute.platformIds.get(j).platformId;
                Station goalTransferStation = railwayData.dataCache.platformIdToStation.get(goalTransferPid);
                if (goalTransferStation == null || goalTransferStation.id != startTransferStation.id) {
                    continue;
                }
                if (!hasGoalAfterIndex(goalRoute, goalPid, j)) {
                    continue;
                }

                int cost = (i - startIndex) + distanceToNextPlatform(goalRoute, goalPid, j);
                if (best == null || cost < best.cost) {
                    best = new TransferCandidate(startTransferPid, goalTransferPid, cost);
                }
            }
        }

        if (best == null) {
            return new ArrayList<>();
        }

        ArrayList<Long> route = new ArrayList<>();
        route.add(startPid);
        if (best.startTransferPid != startPid) {
            route.add(best.startTransferPid);
        }
        if (best.goalTransferPid != best.startTransferPid) {
            route.add(best.goalTransferPid);
        } else {
            route.add(best.goalTransferPid);
        }
        if (goalPid != best.goalTransferPid) {
            route.add(goalPid);
        }
        return normalizeRoute(route);
    }

    private static List<Long> normalizeRoute(List<Long> route) {
        ArrayList<Long> normalized = new ArrayList<>();
        for (Long platformId : route) {
            if (platformId == null || platformId == 0L) {
                continue;
            }
            normalized.add(platformId);
        }
        return normalized;
    }

    private static List<Long> buildUnknownBoardingRouteIds(List<Long> route) {
        ArrayList<Long> result = new ArrayList<>(route.size());
        for (int i = 0; i < route.size(); i++) {
            result.add(-1L);
        }
        return result;
    }

    private static List<Long> buildBoardingRouteIds(RailwayData railwayData, List<Long> route) {
        ArrayList<Long> result = new ArrayList<>(route.size());
        for (int i = 0; i < route.size(); i++) {
            result.add(findNextBoardingRouteId(railwayData, route, i));
        }
        return result;
    }

    private static long findNextBoardingRouteId(RailwayData railwayData, List<Long> route, int index) {
        if (index < 0 || index >= route.size() - 1) {
            return -1L;
        }
        long currentPlatformId = route.get(index);
        for (int nextIndex = index + 1; nextIndex < route.size(); nextIndex++) {
            long nextPlatformId = route.get(nextIndex);
            if (nextPlatformId == currentPlatformId) {
                continue;
            }
            long routeId = findRouteIdBetweenPlatforms(railwayData, currentPlatformId, nextPlatformId);
            if (routeId != -1L) {
                return routeId;
            }
        }
        return -1L;
    }

    private static long findRouteIdBetweenPlatforms(RailwayData railwayData, long startPlatformId, long goalPlatformId) {
        if (startPlatformId == 0L || goalPlatformId == 0L || startPlatformId == -1L || goalPlatformId == -1L) {
            return -1L;
        }
        for (Route route : railwayData.routes) {
            int startIndex = route.getPlatformIdIndex(startPlatformId);
            if (startIndex < 0) {
                continue;
            }
            for (int i = startIndex + 1; i < route.platformIds.size(); i++) {
                if (route.platformIds.get(i).platformId == goalPlatformId) {
                    return route.id;
                }
            }
        }
        return -1L;
    }

    private static boolean hasGoalAfterIndex(Route route, long goalPid, int fromIndex) {
        return distanceToNextPlatform(route, goalPid, fromIndex) < Integer.MAX_VALUE;
    }

    private static int distanceToNextPlatform(Route route, long targetPid, int fromIndex) {
        for (int i = fromIndex + 1; i < route.platformIds.size(); i++) {
            if (route.platformIds.get(i).platformId == targetPid) {
                return i - fromIndex;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static final class TransferCandidate {
        private final long startTransferPid;
        private final long goalTransferPid;
        private final int cost;

        private TransferCandidate(long startTransferPid, long goalTransferPid, int cost) {
            this.startTransferPid = startTransferPid;
            this.goalTransferPid = goalTransferPid;
            this.cost = cost;
        }
    }

}
