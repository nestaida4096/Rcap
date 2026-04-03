package botamochi129.botamochi.rcap.block.entity;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.data.RidingPosManager;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;

public class RidingPosBlockEntity extends BlockEntity {
    private long platformId = -1L;

    public RidingPosBlockEntity(BlockPos pos, BlockState state) {
        super(Rcap.RIDING_POS_BLOCK_ENTITY, pos, state);
    }

    public long getPlatformId() {
        return platformId;
    }

    public void setPlatformId(long platformId) {
        this.platformId = platformId;
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
        }
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putLong("PlatformId", platformId);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        if (nbt.contains("PlatformId")) {
            this.platformId = nbt.getLong("PlatformId");
        }
    }

    public static void tick(World world, BlockPos pos, BlockState state, RidingPosBlockEntity blockEntity) {
        RidingPosManager.registerRidingPos(blockEntity);
    }

    @Override
    public void markRemoved() {
        super.markRemoved();
        if (world != null && !world.isClient) {
            RidingPosManager.unregisterRidingPos(this);
        }
    }
}
