package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.HousingBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

public class HousingManager {

    private static final Set<BlockPos> housingPositions = new HashSet<>();

    public static void registerHousing(BlockPos pos) {
        housingPositions.add(pos);
    }

    public static void unregisterHousing(BlockPos pos) {
        housingPositions.remove(pos);
    }

    public static List<HousingBlockEntity> getAll(World world) {
        List<HousingBlockEntity> result = new ArrayList<>();
        for (BlockPos pos : housingPositions) {
            if (!world.isChunkLoaded(pos)) continue;

            var be = world.getBlockEntity(pos);
            if (be instanceof HousingBlockEntity housing) {
                result.add(housing);
            }
        }
        return result;
    }

    public static void clear() {
        housingPositions.clear();
    }
}
