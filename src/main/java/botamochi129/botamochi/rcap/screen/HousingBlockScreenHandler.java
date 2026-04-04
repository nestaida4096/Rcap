package botamochi129.botamochi.rcap.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class HousingBlockScreenHandler extends ScreenHandler {
    public record OfficeOption(long posLong, String label) {}

    private final BlockPos pos;
    private final int householdSize;
    private final long linkedOfficePosLong;
    private final List<OfficeOption> officeOptions;

    public HousingBlockScreenHandler(int syncId, PlayerInventory inventory, PacketByteBuf buf) {
        this(syncId, inventory, buf.readBlockPos(), buf.readInt(), buf.readLong(), readOfficeOptions(buf));
    }

    public HousingBlockScreenHandler(int syncId, PlayerInventory inventory, BlockPos pos, int householdSize, long linkedOfficePosLong, List<OfficeOption> officeOptions) {
        super(ModScreens.HOUSING_SCREEN_HANDLER, syncId);
        this.pos = pos;
        this.householdSize = householdSize;
        this.linkedOfficePosLong = linkedOfficePosLong;
        this.officeOptions = List.copyOf(officeOptions);
    }

    public int getHouseholdSize() {
        return householdSize;
    }

    public BlockPos getPos() {
        return pos;
    }

    public long getLinkedOfficePosLong() {
        return linkedOfficePosLong;
    }

    public List<OfficeOption> getOfficeOptions() {
        return officeOptions;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public ItemStack transferSlot(PlayerEntity player, int index) {
        return ItemStack.EMPTY;
    }

    private static List<OfficeOption> readOfficeOptions(PacketByteBuf buf) {
        int count = buf.readVarInt();
        List<OfficeOption> options = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            options.add(new OfficeOption(buf.readLong(), buf.readString(128)));
        }
        return options;
    }
}
