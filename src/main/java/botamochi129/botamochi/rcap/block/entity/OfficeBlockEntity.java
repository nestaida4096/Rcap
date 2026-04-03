package botamochi129.botamochi.rcap.block.entity;

import botamochi129.botamochi.rcap.Rcap;
import botamochi129.botamochi.rcap.data.OfficeManager;
import botamochi129.botamochi.rcap.screen.OfficeBlockScreenHandler;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OfficeBlockEntity extends BlockEntity implements ExtendedScreenHandlerFactory {
    private int staffCount = 1;
    private final List<UUID> assigned = new ArrayList<>();

    public OfficeBlockEntity(BlockPos pos, BlockState state) {
        super(Rcap.OFFICE_BLOCK_ENTITY, pos, state);
    }

    public int getStaffCount() {
        return staffCount;
    }
    public void setStaffCount(int count) {
        this.staffCount = count;
        markDirty();
    }

    public boolean hasRoom() {
        return assigned.size() < staffCount;
    }

    public void assignPassenger(UUID uuid) {
        if (!assigned.contains(uuid)) assigned.add(uuid);
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("StaffCount", staffCount);

        // UUID保存
        NbtList list = new NbtList();
        for (UUID id : assigned) {
            list.add(NbtHelper.fromUuid(id));
        }
        nbt.put("Assigned", list);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        staffCount = nbt.getInt("StaffCount");

        assigned.clear();
        NbtList list = nbt.getList("Assigned", NbtElement.INT_ARRAY_TYPE);
        for (NbtElement elem : list) {
            assigned.add(NbtHelper.toUuid(elem));
        }
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("従業員人数設定");
    }
    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(this.getPos());
        buf.writeInt(this.staffCount);
    }
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory inventory, PlayerEntity player) {
        return new OfficeBlockScreenHandler(syncId, inventory, this.getPos(), this.staffCount);
    }

    public static <T extends BlockEntity> void tick(World world, BlockPos pos, BlockState state, T blockEntity) {
        if (!world.isClient && blockEntity instanceof OfficeBlockEntity officeBlockEntity) {
            OfficeManager.register(officeBlockEntity);
        }
    }
}
