package botamochi129.botamochi.rcap.client.render;

import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TrainCarPoseTracker {
    private static final Map<Long, Pose> POSES = new ConcurrentHashMap<>();
    private static final long STALE_AFTER_MS = 3_000L;

    private TrainCarPoseTracker() {
    }

    public static void update(long trainId, int carIndex, double x, double y, double z, float yaw, float pitch) {
        long key = toKey(trainId, carIndex);
        double yawRad = yaw;
        double pitchRad = pitch;
        double cosPitch = Math.cos(pitchRad);
        Vec3d forward = new Vec3d(
                Math.sin(yawRad) * cosPitch,
                Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch
        );
        POSES.put(key, new Pose(new Vec3d(x, y, z), forward, System.currentTimeMillis()));
    }

    public static Pose get(long trainId, int carIndex) {
        Pose pose = POSES.get(toKey(trainId, carIndex));
        if (pose == null) {
            return null;
        }
        if (System.currentTimeMillis() - pose.updatedAtMillis() > STALE_AFTER_MS) {
            POSES.remove(toKey(trainId, carIndex));
            return null;
        }
        return pose;
    }

    private static long toKey(long trainId, int carIndex) {
        return (trainId << 8) ^ carIndex;
    }

    public record Pose(Vec3d center, Vec3d forward, long updatedAtMillis) {
    }
}
