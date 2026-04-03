package botamochi129.botamochi.rcap.block.entity;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.data.HousingManager;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import botamochi129.botamochi.rcap.screen.HousingBlockScreenHandler;
import mtr.data.Platform;
import mtr.data.RailwayData;
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
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
        System.out.println("【保存】householdSize = " + size);
    }

    public Long getLinkedOfficePosLong() {
        return linkedOfficePosLong;
    }

    public List<Long> getCachedRoute() {
        return cachedRoute;
    }

    public void setCachedRoute(List<Long> route) {
        this.cachedRoute = route;
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
        } else {
            cachedRoute = null;
        }
    }

    public void setLinkedOfficePosLong(Long posLong) {
        this.linkedOfficePosLong = posLong;
        markDirty();

        if (posLong != null && this.world instanceof ServerWorld serverWorld) {
            RailwayData railwayData = RailwayData.getInstance(serverWorld);
            long homePid = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, this.pos, 2000, 1000, 1000);
            Platform homePlatform = railwayData.dataCache.platformIdMap.get(homePid);
            BlockPos homePlatformPos = (homePlatform != null) ? homePlatform.getMidPos() : null;

            BlockPos officeBlockPos = BlockPos.fromLong(posLong);
            long officePid = RailwayData.getClosePlatformId(railwayData.platforms, railwayData.dataCache, officeBlockPos, 2000, 1000, 1000);
            Platform officePlatform = railwayData.dataCache.platformIdMap.get(officePid);
            BlockPos officePlatformPos = (officePlatform != null) ? officePlatform.getMidPos() : null;

            System.out.println("[HousingBlockEntity] 住宅 pos: " + this.pos + ", homePid: " + homePid);
            System.out.println("[HousingBlockEntity] オフィス pos: " + officeBlockPos + ", officePid: " + officePid);
            System.out.println("platformIdMap keys: " + railwayData.dataCache.platformIdMap.keySet());
            System.out.println("Contains homePid? " + railwayData.dataCache.platformIdMap.containsKey(homePid));
            System.out.println("Contains officePid? " + railwayData.dataCache.platformIdMap.containsKey(officePid));

            if (homePlatformPos == null || officePlatformPos == null) {
                System.out.println("[HousingBlockEntity] 有効なプラットフォームが見つからず経路検索できません, homePid=" + homePid + ", officePid=" + officePid);
                this.cachedRoute = null;
                markDirty();
                return;
            }

            List<Long> fallbackRoute = new ArrayList<>();
            fallbackRoute.add(homePid);
            if (officePid != homePid) {
                fallbackRoute.add(officePid);
            }
            setCachedRoute(fallbackRoute);
            System.out.println("[HousingBlockEntity] フォールバック経路を設定: " + fallbackRoute);

            if (railwayData != null && railwayData.railwayDataRouteFinderModule != null) {
                boolean queued = railwayData.railwayDataRouteFinderModule.findRoute(
                        homePlatformPos, officePlatformPos, 60,
                        (routeFinderDataList, duration) -> {
                            System.out.println("[RouteFinder Callback] called, routeFinderDataList.size=" + (routeFinderDataList == null ? 0 : routeFinderDataList.size()));
                            if (routeFinderDataList == null || routeFinderDataList.isEmpty()) {
                                this.cachedRoute = null;
                                markDirty();
                                System.out.println("[HousingBlockEntity] 経路検索コールバック結果がnullまたは空");
                                return;
                            }
                            List<Long> platformIdList = new ArrayList<>();
                            for (var data : routeFinderDataList) {
                                System.out.println("routeFinderData pos=" + data.pos + ", duration=" + data.duration);
                                Long pid = railwayData.dataCache.blockPosToPlatformId.get(data.pos.asLong());
                                if (pid != null && pid != 0) {
                                    platformIdList.add(pid);
                                }
                            }
                            if (!platformIdList.isEmpty()) {
                                setCachedRoute(platformIdList);
                                System.out.println("[HousingBlockEntity] 経路キャッシュ完了: " + platformIdList);
                            } else {
                                this.cachedRoute = null;
                                markDirty();
                                System.out.println("[HousingBlockEntity] プラットフォームIDリストが空");
                            }
                        }
                );
                System.out.println("✅ findRoute() result: " + queued);
                if (!queued) {
                    System.out.println("[HousingBlockEntity] findRoute呼び出し失敗：検索キューが満杯の可能性");
                } else {
                    System.out.println("[HousingBlockEntity] findRoute呼び出し成功：経路検索をキューに追加");
                }
            }
        }
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
        System.out.println("[spawnPassengerWithRoute] called seq=" + seq + ", platformIds=" + platformIdList);
        String worldId = "minecraft:overworld";
        if (!platformIdList.isEmpty() && platformIdList.get(0) != -1L) {
            var world = this.getWorld();
            if (world instanceof ServerWorld serverWorld) {
                worldId = serverWorld.getRegistryKey().getValue().toString(); // ワールドID文字列
            }
        }

        double x = homePos.getX() + 0.5;
        double y = homePos.getY() + 1.0;  // 住宅の高さや高さ調整は適宜
        double z = homePos.getZ() + 0.5;

        Passenger passenger = new Passenger(System.currentTimeMillis(), "Passenger-" + seq, x, y, z, 0xFFFFFF, worldId);
        passenger.route = new ArrayList<>(platformIdList);
        passenger.routeTargetIndex = 0;
        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;
        passenger.homeX = x;
        passenger.homeY = y;
        passenger.homeZ = z;
        passenger.returnRoute.clear();
        for (int i = platformIdList.size() - 1; i >= 0; i--) {
            passenger.returnRoute.add(platformIdList.get(i));
        }
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
        return new HousingBlockScreenHandler(syncId, inventory, this.getPos(), this.householdSize);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(this.getPos());
        buf.writeInt(this.householdSize);
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
}
