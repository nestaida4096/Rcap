package botamochi129.botamochi.rcap.client.mixin;

import botamochi129.botamochi.rcap.client.render.TrainCarPoseTracker;
import mtr.data.TrainClient;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TrainClient.class)
public abstract class TrainClientMixin {

    @Inject(method = "simulateCar", at = @At("HEAD"))
    private void rcap$captureCarPose(
            World world,
            int carIndex,
            float tickDelta,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double prevX,
            double prevY,
            double prevZ,
            float prevYaw,
            float prevPitch,
            boolean doorLeftOpen,
            boolean doorRightOpen,
            double carLength,
            CallbackInfo ci
    ) {
        TrainClient self = (TrainClient) (Object) this;
        TrainCarPoseTracker.update(self.id, carIndex, x, y, z, yaw, pitch);
    }
}
