package botamochi129.botamochi.rcap.block;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;

import botamochi129.botamochi.rcap.block.entity.OfficeBlockEntity;
import botamochi129.botamochi.rcap.data.OfficeManager;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import mtr.data.*;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public class HousingBlock extends BlockWithEntity {
    public HousingBlock(Settings settings) {
        super(settings);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new HousingBlockEntity(pos, state);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient) {
            BlockEntity be = world.getBlockEntity(pos);
            if (!(be instanceof HousingBlockEntity housingBlockEntity)) return ActionResult.SUCCESS;

            player.openHandledScreen(housingBlockEntity);

            OfficeBlockEntity office = null;
            Long linkedOffice = housingBlockEntity.getLinkedOfficePosLong();
            if (linkedOffice != null) {
                office = OfficeManager.getAll().stream().filter(o -> o.getPos().asLong() == linkedOffice).findFirst().orElse(null);
            }
            if (office == null) {
                office = OfficeManager.getRandomAvailableOffice();
                if (office == null) {
                    player.sendMessage(Text.literal("利用可能なオフィスが見つかりません。"), false);
                    return ActionResult.SUCCESS;
                }
                housingBlockEntity.setLinkedOfficePosLong(office.getPos().asLong());
                // ここで経路キャッシュが生成される
                player.sendMessage(Text.literal("オフィス紐付け＆経路再計算を行いました。しばらくしてから乗客が出現します。"), false);
            } else {
                // すでにオフィスが設定されている場合、何もしない
            }

            // 再計算要求・乗客強制生成等はここではせず、経路キャッシュが存在する場合のみspawnPassengersIfTimeで動作

            return ActionResult.SUCCESS;
        }
        return ActionResult.SUCCESS;
    }

    private void spawnPassengerWithRoute(List<Long> platformIdList, BlockPos homePos, long newId, String name, World world) {
        double x = homePos.getX() + 0.5;
        double y = homePos.getY() + 1.0;
        double z = homePos.getZ() + 0.5;

        String worldId = "minecraft:overworld";
        if (!platformIdList.isEmpty() && platformIdList.get(0) != -1L) {
            var railwayData = RailwayData.getInstance(world);
            if (railwayData != null) {
                var firstPlatform = railwayData.dataCache.platformIdMap.get(platformIdList.get(0));
                if (firstPlatform != null) {
                    BlockPos platPos = firstPlatform.getMidPos();
                    x = platPos.getX() + 0.5;
                    y = platPos.getY();
                    z = platPos.getZ() + 0.5;
                }
            }
            worldId = world.getRegistryKey().getValue().toString(); // ワールドID文字列
        }

        Passenger passenger = new Passenger(newId, name, x, y, z, 0xFFFFFF, worldId);
        passenger.route = platformIdList;
        passenger.routeTargetIndex = 0;
        passenger.moveState = Passenger.MoveState.WALKING_TO_PLATFORM;

        // set destination if linked office exists (try to find via OfficeManager using HousingBlockEntity -> linkedOfficePosLong)
        // We cannot get housingBlockEntity instance here, so leave destination as NaN; HousingBlockEntity.spawnPassengerWithRoute handles it when used.
        passenger.destinationX = Double.NaN;
        passenger.destinationY = Double.NaN;
        passenger.destinationZ = Double.NaN;

        synchronized (PassengerManager.PASSENGER_LIST) {
            PassengerManager.PASSENGER_LIST.add(passenger);
        }
        PassengerManager.save();
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
                                                                  BlockEntityType<T> type) {
        return world.isClient ? null : checkType(type, Rcap.HOUSING_BLOCK_ENTITY, HousingBlockEntity::tick);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL ; }
}
