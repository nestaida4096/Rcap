package botamochi129.botamochi.rcap;

import botamochi129.botamochi.rcap.block.HousingBlock;
import botamochi129.botamochi.rcap.block.OfficeBlock;
import botamochi129.botamochi.rcap.block.RidingPosBlock;
import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;
import botamochi129.botamochi.rcap.block.entity.OfficeBlockEntity;
import botamochi129.botamochi.rcap.block.entity.RidingPosBlockEntity;
import botamochi129.botamochi.rcap.data.CompanyManager;
import botamochi129.botamochi.rcap.data.HousingManager;
import botamochi129.botamochi.rcap.network.HousingBlockPacketReceiver;
import botamochi129.botamochi.rcap.network.OfficeBlockPacketReceiver;
import botamochi129.botamochi.rcap.network.RcapServerPackets;
import botamochi129.botamochi.rcap.network.ServerNetworking;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import botamochi129.botamochi.rcap.passenger.PassengerMovement;
import botamochi129.botamochi.rcap.screen.ModScreens;
import mtr.data.RailwayData;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.Material;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Rcap implements ModInitializer {
    public static final String MOD_ID = "rcap";
    public static final Block HOUSING_BLOCK = new HousingBlock(FabricBlockSettings.of(Material.STONE).strength(2.0f));
    public static final Block OFFICE_BLOCK = new OfficeBlock(FabricBlockSettings.of(Material.STONE).strength(2.0f));
    public static BlockEntityType<OfficeBlockEntity> OFFICE_BLOCK_ENTITY;
    public static BlockEntityType<HousingBlockEntity> HOUSING_BLOCK_ENTITY;
    public static final Block RIDING_POS_BLOCK = new RidingPosBlock(FabricBlockSettings.of(Material.STONE).strength(2.0f));
    public static BlockEntityType<RidingPosBlockEntity> RIDING_POS_BLOCK_ENTITY;

    public static final Logger LOGGER = LogManager.getLogger("RCAP");

    // 経路デバッグが有効なプレイヤーのUUIDを保持するスレッドセーフなセット
    private static final Set<UUID> debugPathPlayers = ConcurrentHashMap.newKeySet();

    // 乗客状態ログが有効かどうか
    public static boolean passengerStateLogEnabled = false;

    @Override
    public void onInitialize() {
        LOGGER.info("Loading!");
        Registry.register(Registry.BLOCK, new Identifier(MOD_ID, "housing_block"), HOUSING_BLOCK);
        Registry.register(Registry.ITEM, new Identifier(MOD_ID, "housing_block"),
                new BlockItem(HOUSING_BLOCK, new Item.Settings().group(ItemGroup.BUILDING_BLOCKS)));
        HOUSING_BLOCK_ENTITY = Registry.register(
                Registry.BLOCK_ENTITY_TYPE,
                new Identifier(MOD_ID, "housing_block_entity"),
                FabricBlockEntityTypeBuilder.create(HousingBlockEntity::new, HOUSING_BLOCK).build(null)
        );

        Registry.register(Registry.BLOCK, new Identifier(MOD_ID, "office_block"), OFFICE_BLOCK);
        Registry.register(Registry.ITEM, new Identifier(MOD_ID, "office_block"), new BlockItem(OFFICE_BLOCK, new Item.Settings().group(ItemGroup.BUILDING_BLOCKS)));
        OFFICE_BLOCK_ENTITY = Registry.register(
                Registry.BLOCK_ENTITY_TYPE, new Identifier(MOD_ID, "office_block_entity"),
                BlockEntityType.Builder.create(OfficeBlockEntity::new, OFFICE_BLOCK).build(null)
        );

        Registry.register(Registry.BLOCK, new Identifier(MOD_ID, "riding_pos_block"), RIDING_POS_BLOCK);
        Registry.register(Registry.ITEM, new Identifier(MOD_ID, "riding_pos_block"), new BlockItem(RIDING_POS_BLOCK, new Item.Settings().group(ItemGroup.BUILDING_BLOCKS)));
        RIDING_POS_BLOCK_ENTITY = Registry.register(
                Registry.BLOCK_ENTITY_TYPE,
                new Identifier(MOD_ID, "riding_pos_block_entity"),
                FabricBlockEntityTypeBuilder.create(RidingPosBlockEntity::new, RIDING_POS_BLOCK).build(null)
        );

        ModScreens.registerScreenHandlers();
        // RcapServerPackets.register();
        ServerNetworking.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerWorld world = server.getOverworld();
            if (world != null) {
                CompanyManager.init(world);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            RcapServerPackets.sendCompanyList(handler.getPlayer());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CompanyManager.save();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            HousingManager.clear();
            if (handler.getPlayer() != null) {
                debugPathPlayers.remove(handler.getPlayer().getUuid());
            }
        });

        HousingBlockPacketReceiver.register();
        OfficeBlockPacketReceiver.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerWorld world = server.getOverworld();
            if (world != null) PassengerManager.init(world);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 2 == 0) {
                PassengerManager.broadcastToAllPlayers(server);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tickCount = server.getTicks();

            for (ServerWorld w : server.getWorlds()) {
                long time = w.getTimeOfDay();

                // ★ 設計移行：BlockEntity個別にTickさせるのをやめ、HousingManager から仮想グローバルシミュレーションとして一括処理！
                HousingManager.tickGlobalSimulation(w, time);

                RailwayData railwayData = RailwayData.getInstance(w);
                if (railwayData == null) continue;

                // 不正プラットフォームターゲットの乗客をクリーンアップ (毎ティックではなく 20ティックごと＝1秒ごとに分散して処理)
                if (tickCount % 20 == 0) {
                    PassengerManager.PASSENGER_LIST.removeIf(passenger -> {
                        if (passenger.moveState != Passenger.MoveState.IDLE) return false;
                        if (passenger.route == null || passenger.route.isEmpty()) return true;

                        Long targetPid = null;
                        if (passenger.routeTargetIndex >= 0 && passenger.routeTargetIndex < passenger.route.size()) {
                            targetPid = passenger.route.get(passenger.routeTargetIndex);
                        } else {
                            return true;
                        }
                        return !railwayData.dataCache.platformIdMap.containsKey(targetPid);
                    });
                }

                // 追加待機キューの取り込み
                Passenger p;
                while ((p = PassengerManager.PENDING_ADD_QUEUE.poll()) != null) {
                    PassengerManager.PASSENGER_LIST.add(p);
                }

                // 一時キャッシュ情報の事前クリア
                PassengerMovement.prepareTick();

                // このワールド内でデバッグ表示がONになっているプレイヤーのみを抽出
                List<ServerPlayerEntity> debugViewers = w.getPlayers(player -> debugPathPlayers.contains(player.getUuid()));

                for (Passenger passenger : PassengerManager.PASSENGER_LIST) {
                    if (passenger.worldId.equals(w.getRegistryKey().getValue().toString())) {

                        boolean shouldUpdate = false;
                        if (passenger.moveState == Passenger.MoveState.WALKING_TO_PLATFORM ||
                                passenger.moveState == Passenger.MoveState.WALKING_TO_DESTINATION) {
                            shouldUpdate = (tickCount % 2 == (passenger.id % 2));
                        } else {
                            shouldUpdate = (tickCount % 10 == (passenger.id % 10));
                        }

                        if (shouldUpdate) {
                            try {
                                PassengerMovement.updatePassenger(w, passenger, railwayData);
                            } catch (Exception e) {
                                LOGGER.error("乗客 " + passenger.name + " (" + passenger.id + ") の更新中にエラーが発生しました", e);
                            }
                        }

                        // 経路デバッグ用パーティクル表示ロジック
                        if (!debugViewers.isEmpty()) {
                            if (tickCount % 20 == 0 && passenger.walkPath != null && !passenger.walkPath.isEmpty()) {
                                for (Long posLong : passenger.walkPath) {
                                    BlockPos nodePos = BlockPos.fromLong(posLong);
                                    double px = nodePos.getX() + 0.5;
                                    double py = nodePos.getY() + 0.15;
                                    double pz = nodePos.getZ() + 0.5;

                                    for (ServerPlayerEntity viewer : debugViewers) {
                                        if (viewer.squaredDistanceTo(px, py, pz) < 32 * 32) {
                                            w.spawnParticles(viewer, ParticleTypes.HAPPY_VILLAGER, true, px, py, pz, 1, 0, 0, 0, 0);
                                        }
                                    }
                                }
                            }

                            if (tickCount % 10 == 0 && !Double.isNaN(passenger.destinationX) && !Double.isNaN(passenger.destinationY) && !Double.isNaN(passenger.destinationZ)) {
                                double dx = passenger.destinationX;
                                double dy = passenger.destinationY + 1.1;
                                double dz = passenger.destinationZ;

                                for (ServerPlayerEntity viewer : debugViewers) {
                                    if (viewer.squaredDistanceTo(dx, dy, dz) < 32 * 32) {
                                        w.spawnParticles(viewer, ParticleTypes.SOUL_FIRE_FLAME, true, dx, dy, dz, 1, 0, 0.05, 0, 0.01);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        // コマンド登録処理
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("rcap_clear_passengers")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        int removed = PassengerManager.clearAll(context.getSource().getServer());
                        context.getSource().sendFeedback(Text.literal("§a[RCAP] §f" + removed + "人の乗客を削除しました。"), true);
                        return removed;
                    })
            );

            // 経路デバッグオーバーレイの表示切り替えコマンド
            dispatcher.register(CommandManager.literal("rcap_debug_paths")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayer();
                        if (player != null) {
                            UUID uuid = player.getUuid();
                            if (debugPathPlayers.contains(uuid)) {
                                debugPathPlayers.remove(uuid);
                                context.getSource().sendFeedback(Text.literal("§a[RCAP] §f乗客の経路デバッグ表示を§c無効§fにしました。"), false);
                            } else {
                                debugPathPlayers.add(uuid);
                                context.getSource().sendFeedback(Text.literal("§a[RCAP] §f乗客の経路デバッグ表示を§a有効§fにしました。"), false);
                            }
                        }
                        return 1;
                    })
            );

            // 乗客状態変化ログの表示切り替えコマンド
            dispatcher.register(CommandManager.literal("rcap_debug_log")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(context -> {
                        passengerStateLogEnabled = !passengerStateLogEnabled;
                        String state = passengerStateLogEnabled ? "§a有効" : "§c無効";
                        context.getSource().sendFeedback(Text.literal("§a[RCAP] §f乗客の状態変化ログを" + state + "§fにしました。"), false);
                        return 1;
                    })
            );
        });
    }
}