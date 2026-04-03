package botamochi129.botamochi.rcap.mixin;

import mtr.data.Train;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Train.class)
public interface TrainAccessor {
    @Invoker("getRoutePosition")
    Vec3d callGetRoutePosition(int carIndex, int spacing);
}