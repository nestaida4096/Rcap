package botamochi129.botamochi.rcap.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

public class OfficeBlockScreenHandler extends ScreenHandler {
    private final BlockPos pos;
    private final int staffCount;

    public OfficeBlockScreenHandler(int syncId, PlayerInventory inventory, PacketByteBuf buf) {
        this(syncId, inventory, buf.readBlockPos(), buf.readInt());
    }
    public OfficeBlockScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos, int staffCount) {
        super(ModScreens.OFFICE_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.staffCount = staffCount;
    }
    public int getOfficeSize() { return staffCount; }
    public BlockPos getPos() { return pos; }
    @Override
    public boolean canUse(PlayerEntity player) { return true; }
    @Override
    public ItemStack transferSlot(PlayerEntity player, int index) { return ItemStack.EMPTY; }
}
