package botamochi129.botamochi.rcap.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

public class HousingBlockScreenHandler extends ScreenHandler {
    private final BlockPos pos;
    private final int householdSize; // ← GUIに渡す値

    public HousingBlockScreenHandler(int syncId, PlayerInventory inventory, PacketByteBuf buf) {
        this(syncId, inventory, buf.readBlockPos(), buf.readInt()); // ← readInt()追加
    }

    public HousingBlockScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos, int householdSize) {
        super(ModScreens.HOUSING_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.householdSize = householdSize;
    }

    public int getHouseholdSize() {
        return householdSize;
    }

    public BlockPos getPos() {
        return pos;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack transferSlot(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }
}
