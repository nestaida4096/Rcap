// (省略せずファイル全体を貼っています — 実際のプロジェクトでは既存の PassengerRenderer を置き換えてください)
package botamochi129.botamochi.rcap.client.render;

import botamochi129.botamochi.rcap.mixin.TrainAccessor;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import mtr.client.ClientData;
import mtr.data.Platform;
import mtr.data.TrainClient;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3f;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public class PassengerRenderer {
    private static PassengerModel playerModel = null;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            if (playerModel == null) {
                playerModel = new PassengerModel(
                        client.getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER),
                        false
                );
            }

            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            var camera = context.camera();

            List<Passenger> passengers;
            synchronized (PassengerManager.PASSENGER_LIST) {
                passengers = new ArrayList<>(PassengerManager.PASSENGER_LIST);
            }

            long now = System.currentTimeMillis();

            // Occupancy map for this render frame:
            // trainId -> (carIndex -> set of "idxLong,idxLat" strings)
            final Map<Long, Map<Integer, Set<String>>> frameOccupied = new HashMap<>();

            // grid cell sizes (meters)
            // 前後方向: 1.0m, 左右方向: 0.5m
            final double LONG_CELL = 0.9;
            final double LAT_CELL = 0.5;
            final double LONGITUDINAL_MARGIN = 1.0;

            for (Passenger passenger : passengers) {
                // computed orientation to apply to whole model (matrix) for on-train passengers
                Double computedYawDeg = null;
                Double computedPitchDeg = null;
                boolean snapToTrainTarget = false;

                // --- if passenger is on train and has a car index, follow that car and compute full-body rotation & in-car position ---
                if (passenger.moveState == Passenger.MoveState.ON_TRAIN) {
                    TrainClient matchedTrain = null;
                    if (passenger.currentTrainId != null) matchedTrain = findTrainClientById(passenger.currentTrainId);

                    // If we don't have a train yet, try to find one
                    if (matchedTrain == null && passenger.currentTrainId == null) {
                        matchedTrain = findTrainClientByScheduledRouteOrProximity(passenger);
                        if (matchedTrain != null) passenger.currentTrainId = matchedTrain.id;
                    }

                    if (matchedTrain != null) {
                        try {
                            int carCount = getTrainCarCount(matchedTrain);

                            // Ensure we have a carIndex assigned; if not, try to detect by proximity.
                            if (passenger.currentCarIndex < 0) {
                                snapToTrainTarget = true;
                                int bestIndex = -1;
                                double bestDist = Double.MAX_VALUE;
                                double refX = Double.isNaN(passenger.boardingX) ? passenger.x : passenger.boardingX;
                                double refY = Double.isNaN(passenger.boardingY) ? passenger.y : passenger.boardingY;
                                double refZ = Double.isNaN(passenger.boardingZ) ? passenger.z : passenger.boardingZ;
                                for (int i = 0; i < carCount; i++) {
                                    try {
                                        Vec3d carAnchor = ((TrainAccessor) matchedTrain).callGetRoutePosition(i, matchedTrain.spacing);
                                        Vec3d forward = computeCarForwardVector(matchedTrain, i);
                                        if (forward == null) forward = new Vec3d(0, 0, 1);
                                        Vec3d horizontalForward = new Vec3d(forward.x, 0.0, forward.z);
                                        double hfLen = Math.sqrt(horizontalForward.x * horizontalForward.x + horizontalForward.z * horizontalForward.z);
                                        if (hfLen == 0) horizontalForward = new Vec3d(0, 0, 1);
                                        else horizontalForward = new Vec3d(horizontalForward.x / hfLen, 0.0, horizontalForward.z / hfLen);
                                        double spacing = safeGetDoubleField(matchedTrain, "spacing", (double) matchedTrain.spacing);
                                        Vec3d carCenter = getCarInteriorCenter(carAnchor, horizontalForward, forward, spacing, isTrainReversed(matchedTrain));
                                        double dx = carCenter.x - refX;
                                        double dy = carCenter.y - refY;
                                        double dz = carCenter.z - refZ;
                                        double d2 = dx*dx + dy*dy + dz*dz;
                                        if (d2 < bestDist) {
                                            bestDist = d2;
                                            bestIndex = i;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                if (bestIndex >= 0) {
                                    passenger.currentCarIndex = bestIndex;
                                } else {
                                    // fallback: distribute across cars deterministically by passenger id
                                    if (carCount > 0) passenger.currentCarIndex = (int) (Math.floorMod(passenger.id, carCount));
                                }
                            }

                            // Now compute placement inside that car and orientation
                            if (passenger.currentCarIndex >= 0 && (carCount == 1 ? passenger.currentCarIndex == 0 : passenger.currentCarIndex < carCount)) {
                                int carIdx = passenger.currentCarIndex;
                                Vec3d carAnchor = ((TrainAccessor) matchedTrain).callGetRoutePosition(carIdx, matchedTrain.spacing);

                                // derive robust forward vector from neighbouring positions (prefer this)
                                Vec3d forward = computeCarForwardVector(matchedTrain, carIdx);
                                if (forward == null) {
                                    // fallback to yaw/pitch if forward sampling failed
                                    Double yawDegFb = tryGetTrainYawDeg(matchedTrain, carIdx);
                                    Double pitchDegFb = tryGetTrainPitchDeg(matchedTrain, carIdx);
                                    if (yawDegFb != null) {
                                        double yawRad = Math.toRadians(yawDegFb);
                                        double fx = Math.sin(yawRad);
                                        double fz = Math.cos(yawRad);
                                        double fy = 0.0;
                                        if (pitchDegFb != null) {
                                            fy = Math.sin(Math.toRadians(pitchDegFb));
                                        }
                                        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
                                        forward = len == 0 ? new Vec3d(0, 0, 1) : new Vec3d(fx / len, fy / len, fz / len);
                                    } else {
                                        // additional fallback for single-car or missing yaw: try using vector to nearest other train
                                        Vec3d nearestOther = findNearestOtherTrainVector(matchedTrain, carAnchor);
                                        if (nearestOther != null) forward = nearestOther;
                                        else forward = new Vec3d(0, 0, 1);
                                    }
                                }

                                // Use horizontal forward for placement grid (project out vertical)
                                Vec3d horizontalForward = new Vec3d(forward.x, 0.0, forward.z);
                                double hfLen = Math.sqrt(horizontalForward.x * horizontalForward.x + horizontalForward.z * horizontalForward.z);
                                if (hfLen == 0) horizontalForward = new Vec3d(0, 0, 1);
                                else horizontalForward = new Vec3d(horizontalForward.x / hfLen, 0.0, horizontalForward.z / hfLen);

                                // lateral (right vector) in horizontal plane
                                Vec3d lateral = new Vec3d(horizontalForward.z, 0.0, -horizontalForward.x);

                                // compute yaw/pitch for whole-body rotation based on horizontalForward and forward.y
                                double yawRad = Math.atan2(horizontalForward.x, horizontalForward.z);
                                double yawDeg = Math.toDegrees(yawRad);
                                double pitchDeg = Math.toDegrees(Math.asin(clamp(forward.y, -1.0, 1.0)));

                                // Decide left/right facing (per passenger)
                                boolean faceRight = ((passenger.id & 1L) == 0L);
                                computedYawDeg = yawDeg + (faceRight ? 90.0 : -90.0);
                                computedPitchDeg = pitchDeg;

                                // read spacing & width (reflection, with fallbacks)
                                double spacing = safeGetDoubleField(matchedTrain, "spacing", (double) matchedTrain.spacing);
                                double width = safeGetDoubleField(matchedTrain, "width", 2.0);

                                Vec3d center = getCarInteriorCenter(carAnchor, horizontalForward, forward, spacing, isTrainReversed(matchedTrain));

                                // Prepare frame occupancy for this train/car
                                frameOccupied.computeIfAbsent(matchedTrain.id, k -> new HashMap<>());
                                Map<Integer, Set<String>> trainOcc = frameOccupied.get(matchedTrain.id);
                                trainOcc.computeIfAbsent(carIdx, k -> new HashSet<>());

                                double usableLength = Math.max(LONG_CELL, spacing - LONGITUDINAL_MARGIN * 2.0);
                                int intSpacing = Math.max(1, (int) Math.floor(usableLength / LONG_CELL));
                                int intWidth = Math.max(1, (int)Math.round(width / LAT_CELL));

                                // pick a cell with probing to avoid collisions
                                int[] chosen = pickAvailableCellIndex(passenger, matchedTrain, carIdx, intSpacing, intWidth, trainOcc.get(carIdx));
                                int idxLong = chosen[0];
                                int idxLat = chosen[1];

                                double startLong = -((intSpacing - 1) / 2.0) * LONG_CELL;
                                double startLat = -((intWidth - 1) / 2.0) * LAT_CELL;
                                double longitudinal = startLong + idxLong * LONG_CELL;
                                double lateralInt = startLat + idxLat * LAT_CELL;

                                // convert to world offset using horizontalForward & lateral (both unit length)
                                double offX = horizontalForward.x * longitudinal + lateral.x * lateralInt;
                                double offY = horizontalForward.y * longitudinal + lateral.y * lateralInt;
                                double offZ = horizontalForward.z * longitudinal + lateral.z * lateralInt;

                                // small deterministic vertical jitter
                                long seed = passenger.id ^ (matchedTrain.id * 31L) ^ (carIdx * 131);
                                Random r = new Random(seed);
                                double vert = ((r.nextDouble() - 0.5) * 0.2);
                                offY += vert;

                                double targetX = center.x + offX;
                                double targetY = center.y + offY + 1.0; // base height above center
                                double targetZ = center.z + offZ;

                                if (snapToTrainTarget) {
                                    passenger.x = targetX;
                                    passenger.y = targetY;
                                    passenger.z = targetZ;
                                } else {
                                    // Smoothly move passenger towards target once the initial seat placement is resolved.
                                    applySmoothPosition(passenger, targetX, targetY, targetZ, 0.6);
                                }
                            }
                        } catch (Throwable t) {
                            passenger.currentCarIndex = -1;
                            computedYawDeg = null;
                            computedPitchDeg = null;
                        }
                    }
                }

                // --- fallback interpolation/teleport logic (unchanged) but avoid overwriting currentTrainId when already set ---
                if (passenger.moveState == Passenger.MoveState.ON_TRAIN && (passenger.currentTrainId == null || passenger.currentCarIndex < 0)) {
                    long bt = passenger.boardingTimeMillis;
                    long at = passenger.alightTimeMillis;
                    double bx = passenger.boardingX;
                    double by = passenger.boardingY;
                    double bz = passenger.boardingZ;
                    double ax = passenger.alightX;
                    double ay = passenger.alightY;
                    double az = passenger.alightZ;

                    boolean usedInterpolation = false;

                    if (!Double.isNaN(bx) && !Double.isNaN(ax) && at > bt && bt > 0) {
                        double t = Math.min(1.0, Math.max(0.0, (double)(now - bt) / (double)(at - bt)));
                        double ix = bx + (ax - bx) * t;
                        double iy = by + (ay - by) * t;
                        double iz = bz + (az - bz) * t;
                        applySmoothPosition(passenger, ix, iy, iz, 0.6);
                        usedInterpolation = true;
                    } else {
                        long bpid = passenger.boardingPlatformId;
                        long apid = passenger.alightingPlatformId;

                        if (bpid != -1 && apid != -1 && bt > 0 && at > bt) {
                            Platform bp = ClientData.DATA_CACHE.platformIdMap.get(bpid);
                            Platform ap = ClientData.DATA_CACHE.platformIdMap.get(apid);
                            if (bp != null && ap != null) {
                                Vec3d bpPos = new Vec3d(bp.getMidPos().getX() + 0.5, bp.getMidPos().getY() + 1.0, bp.getMidPos().getZ() + 0.5);
                                Vec3d apPos = new Vec3d(ap.getMidPos().getX() + 0.5, ap.getMidPos().getY() + 1.0, ap.getMidPos().getZ() + 0.5);
                                double t = Math.min(1.0, Math.max(0.0, (double)(now - bt) / (double)(at - bt)));
                                double ix = bpPos.x + (apPos.x - bpPos.x) * t;
                                double iy = bpPos.y + (apPos.y - bpPos.y) * t;
                                double iz = bpPos.z + (apPos.z - bpPos.z) * t;
                                applySmoothPosition(passenger, ix, iy, iz, 0.6);
                                usedInterpolation = true;
                            }
                        }
                    }

                    if (!usedInterpolation) {
                        TrainClient found = null;
                        double best = Double.MAX_VALUE;
                        for (TrainClient trainClient : ClientData.TRAINS) {
                            if (trainClient == null) continue;
                            Vec3d posRaw;
                            try {
                                posRaw = ((TrainAccessor) trainClient).callGetRoutePosition(0, trainClient.spacing);
                            } catch (Throwable t) {
                                continue;
                            }
                            double dx = posRaw.x - passenger.x;
                            double dy = posRaw.y - passenger.y;
                            double dz = posRaw.z - passenger.z;
                            double d2 = dx*dx + dy*dy + dz*dz;
                            if (d2 < best && d2 < 64 * 64) {
                                best = d2;
                                found = trainClient;
                            }
                        }
                        if (found != null) {
                            // Only set currentTrainId if it was previously unset.
                            // This prevents passengers from switching between multiple trains every frame.
                            if (passenger.currentTrainId == null) {
                                passenger.currentTrainId = found.id;
                            }
                        } else {
                            boolean teleported = false;
                            if (!Double.isNaN(bx) && !Double.isNaN(by) && !Double.isNaN(bz) &&
                                    !Double.isNaN(ax) && !Double.isNaN(ay) && !Double.isNaN(az) && at > bt && bt > 0) {
                                double t = Math.min(1.0, Math.max(0.0, (double)(now - bt) / (double)(at - bt)));
                                if (t < 0.5) {
                                    applySmoothPosition(passenger, bx, by, bz, 0.6);
                                } else {
                                    applySmoothPosition(passenger, ax, ay, az, 0.6);
                                }
                                teleported = true;
                            } else {
                                long bpid = passenger.boardingPlatformId;
                                long apid = passenger.alightingPlatformId;
                                Platform bp = null;
                                Platform ap = null;
                                if (bpid != -1) bp = ClientData.DATA_CACHE.platformIdMap.get(bpid);
                                if (apid != -1) ap = ClientData.DATA_CACHE.platformIdMap.get(apid);

                                if (bp != null && ap != null && at > bt && bt > 0) {
                                    double t = Math.min(1.0, Math.max(0.0, (double)(now - bt) / (double)(at - bt)));
                                    if (t < 0.5) {
                                        var mid = bp.getMidPos();
                                        applySmoothPosition(passenger, mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5, 0.6);
                                    } else {
                                        var mid = ap.getMidPos();
                                        applySmoothPosition(passenger, mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5, 0.6);
                                    }
                                    teleported = true;
                                } else if (bp != null) {
                                    var mid = bp.getMidPos();
                                    applySmoothPosition(passenger, mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5, 0.6);
                                    teleported = true;
                                } else if (ap != null) {
                                    var mid = ap.getMidPos();
                                    applySmoothPosition(passenger, mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5, 0.6);
                                    teleported = true;
                                } else {
                                    long nearestPid = Long.MIN_VALUE;
                                    double bestDist = Double.MAX_VALUE;
                                    for (Platform p : ClientData.DATA_CACHE.platformIdMap.values()) {
                                        var mid = p.getMidPos();
                                        double px = mid.getX() + 0.5;
                                        double py = mid.getY() + 1.0;
                                        double pz = mid.getZ() + 0.5;
                                        double dx = px - passenger.x;
                                        double dy = py - passenger.y;
                                        double dz = pz - passenger.z;
                                        double d2 = dx * dx + dy * dy + dz * dz;
                                        if (d2 < bestDist) {
                                            bestDist = d2;
                                            nearestPid = p.id;
                                        }
                                    }
                                    if (nearestPid != Long.MIN_VALUE) {
                                        Platform nearest = ClientData.DATA_CACHE.platformIdMap.get(nearestPid);
                                        if (nearest != null) {
                                            var mid = nearest.getMidPos();
                                            applySmoothPosition(passenger, mid.getX() + 0.5, mid.getY() + 1.0, mid.getZ() + 0.5, 0.6);
                                            teleported = true;
                                        }
                                    }
                                }
                            }

                            if (teleported) {
                                passenger.x += (Math.random() - 0.5) * 0.5;
                                passenger.z += (Math.random() - 0.5) * 0.5;
                                System.out.println("PassengerRenderer: teleported passenger " + passenger.id + " to nearest platform for visual stability");
                            }
                        }
                    }
                }

                // render passenger
                double dx = passenger.x - camera.getPos().x;
                double dy = passenger.y - camera.getPos().y;
                double dz = passenger.z - camera.getPos().z;

                if (dx * dx + dy * dy + dz * dz > 64 * 64) continue;

                BlockPos pos = new BlockPos(Math.floor(passenger.x), Math.floor(passenger.y), Math.floor(passenger.z));
                int lightLevel = context.world().getLightLevel(pos);
                int light = LightmapTextureManager.pack(lightLevel, 0);

                matrices.push();
                // translate to camera-relative position
                matrices.translate(dx, dy + 1.5, dz);

                // apply flip used previously so model faces correct direction in world
                matrices.scale(-1f, -1f, 1f);

                // If we computed yaw/pitch from train, rotate the whole model accordingly.
                if (computedYawDeg != null && computedPitchDeg != null) {
                    matrices.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion((computedYawDeg).floatValue()));
                    matrices.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion((computedPitchDeg).floatValue()));
                }

                // Ensure model animations are updated and do not apply head-only rotations (we rotated whole matrix)
                playerModel.setAngles(null, 0f, 0f, 0f, 0f, 0f);

                Identifier skinToUse = Passenger.SKINS[Math.max(0, Math.min(passenger.skinIndex, Passenger.SKINS.length-1))];
                playerModel.render(
                        matrices,
                        consumers.getBuffer(RenderLayer.getEntityTranslucentCull(skinToUse)),
                        light,
                        OverlayTexture.DEFAULT_UV,
                        1f, 1f, 1f, 1f
                );
                matrices.pop();
            }
        });
    }

    private static TrainClient findTrainClientById(Long id) {
        if (id == null) return null;
        for (TrainClient trainClient : ClientData.TRAINS) {
            if (trainClient != null && trainClient.id == id) return trainClient;
        }
        return null;
    }

    private static TrainClient findTrainClientByScheduledRouteOrProximity(Passenger passenger) {
        if (passenger.scheduledRouteId != -1L) {
            for (TrainClient trainClient : ClientData.TRAINS) {
                if (trainClient == null) continue;
                try {
                    long trainRouteId = Long.MIN_VALUE;
                    try {
                        Field f = trainClient.getClass().getField("routeId");
                        f.setAccessible(true);
                        Object v = f.get(trainClient);
                        if (v instanceof Number) trainRouteId = ((Number) v).longValue();
                    } catch (NoSuchFieldException ignored) {
                        try {
                            Method m = trainClient.getClass().getMethod("getRouteId");
                            Object v = m.invoke(trainClient);
                            if (v instanceof Number) trainRouteId = ((Number) v).longValue();
                        } catch (Throwable ignored2) {}
                    }
                    if (trainRouteId != Long.MIN_VALUE && trainRouteId == passenger.scheduledRouteId) return trainClient;
                } catch (Throwable ignored) {}
            }
        }

        double refX = Double.isNaN(passenger.boardingX) ? passenger.x : passenger.boardingX;
        double refY = Double.isNaN(passenger.boardingY) ? passenger.y : passenger.boardingY;
        double refZ = Double.isNaN(passenger.boardingZ) ? passenger.z : passenger.boardingZ;

        TrainClient nearest = null;
        double best = Double.MAX_VALUE;
        for (TrainClient trainClient : ClientData.TRAINS) {
            if (trainClient == null) continue;
            try {
                Vec3d posRaw = ((TrainAccessor) trainClient).callGetRoutePosition(0, trainClient.spacing);
                double dx = posRaw.x - refX;
                double dy = posRaw.y - refY;
                double dz = posRaw.z - refZ;
                double d2 = dx*dx + dy*dy + dz*dz;
                if (d2 < best) {
                    best = d2;
                    nearest = trainClient;
                }
            } catch (Throwable ignored) {}
        }
        if (nearest != null && best < 128 * 128) return nearest;
        return null;
    }

    private static int getTrainCarCount(TrainClient trainClient) {
        if (trainClient == null) return 0;
        try {
            Field f = null;
            try {
                f = trainClient.getClass().getField("cars");
            } catch (NoSuchFieldException ignored) {
                try {
                    f = trainClient.getClass().getDeclaredField("cars");
                } catch (NoSuchFieldException ignored2) {
                    f = null;
                }
            }
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(trainClient);
                if (val instanceof Collection) return ((Collection<?>) val).size();
                if (val != null && val.getClass().isArray()) return Array.getLength(val);
            }
        } catch (Throwable ignored) {}

        String[] intNames = new String[]{"trainCars", "carCount", "carsCount", "carsNumber", "numCars"};
        for (String name : intNames) {
            try {
                Field f = trainClient.getClass().getField(name);
                f.setAccessible(true);
                Object v = f.get(trainClient);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
        }

        String[] getterNames = new String[]{"getTrainCars", "getCarCount", "getNumCars", "getCarsCount"};
        for (String mname : getterNames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {}
        }

        return 1;
    }

    // Try reading yaw (degrees) from TrainClient via reflection. Try common names first.
    private static Double tryGetTrainYawDeg(TrainClient trainClient, int carIndex) {
        String[] names = new String[]{"yaw", "carYaw", "rotationYaw", "yawDeg", "car_yaw"};
        for (String n : names) {
            try {
                Field f = trainClient.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }
        String[] mnames = new String[]{"getYaw", "getCarYaw", "getRotationYaw"};
        for (String mname : mnames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // Try reading pitch (degrees) from TrainClient via reflection.
    private static Double tryGetTrainPitchDeg(TrainClient trainClient, int carIndex) {
        String[] names = new String[]{"pitch", "carPitch", "rotationPitch", "pitchDeg"};
        for (String n : names) {
            try {
                Field f = trainClient.getClass().getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }
        String[] mnames = new String[]{"getPitch", "getCarPitch", "getRotationPitch"};
        for (String mname : mnames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    // Compute forward vector for a car by sampling positions around the car index (preferred).
    private static Vec3d computeCarForwardVector(TrainClient trainClient, int carIndex) {
        try {
            int carCount = getTrainCarCount(trainClient);
            int prevIndex = Math.max(0, carIndex - 1);
            int nextIndex = Math.min(carCount - 1, carIndex + 1);
            Vec3d prev = ((TrainAccessor) trainClient).callGetRoutePosition(prevIndex, trainClient.spacing);
            Vec3d next = ((TrainAccessor) trainClient).callGetRoutePosition(nextIndex, trainClient.spacing);
            Vec3d dir = new Vec3d(next.x - prev.x, next.y - prev.y, next.z - prev.z);
            double len = Math.sqrt(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
            if (len == 0) return null;
            return new Vec3d(dir.x / len, dir.y / len, dir.z / len);
        } catch (Throwable t) {
            return null;
        }
    }

    // Try to get a vector from this car front to nearest other train front (used as fallback for single-car orientation)
    private static Vec3d findNearestOtherTrainVector(TrainClient self, Vec3d selfFront) {
        Vec3d bestVec = null;
        double bestDist = Double.MAX_VALUE;
        for (TrainClient tc : ClientData.TRAINS) {
            if (tc == null) continue;
            if (tc.id == self.id) continue;
            try {
                Vec3d otherFront = ((TrainAccessor) tc).callGetRoutePosition(0, tc.spacing);
                double dx = otherFront.x - selfFront.x;
                double dy = otherFront.y - selfFront.y;
                double dz = otherFront.z - selfFront.z;
                double d2 = dx*dx + dy*dy + dz*dz;
                if (d2 < bestDist && d2 > 0.0001) {
                    bestDist = d2;
                    double len = Math.sqrt(d2);
                    bestVec = new Vec3d(dx/len, dy/len, dz/len);
                }
            } catch (Throwable ignored) {}
        }
        return bestVec;
    }

    // pick an available cell index (idxLong, idxLat) with linear probing to avoid collisions
    private static int[] pickAvailableCellIndex(Passenger passenger, TrainClient trainClient, int carIndex, int intSpacing, int intWidth, Set<String> occupiedSet) {
        if (occupiedSet == null) occupiedSet = new HashSet<>();
        // deterministic initial indexes from seed including carIndex
        long seed = passenger.id ^ (trainClient.id * 31L) ^ (carIndex * 131);
        Random r = new Random(seed);
        int idxLongInit = r.nextInt(intSpacing);
        int idxLatInit = r.nextInt(intWidth);

        // probing order: linear probing with small wrap
        int maxAttempts = intSpacing * intWidth;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int idxLong = (idxLongInit + attempt) % intSpacing;
            int idxLat = (idxLatInit + (attempt / intSpacing)) % intWidth;
            if (idxLong < 0) idxLong += intSpacing;
            if (idxLat < 0) idxLat += intWidth;

            String key = idxLong + "," + idxLat;
            if (!occupiedSet.contains(key)) {
                occupiedSet.add(key);
                return new int[]{idxLong, idxLat};
            }
        }

        // fallback: brute force find any free cell (shouldn't normally happen)
        for (int i = 0; i < intSpacing; i++) {
            for (int j = 0; j < intWidth; j++) {
                String key = i + "," + j;
                if (!occupiedSet.contains(key)) {
                    occupiedSet.add(key);
                    return new int[]{i, j};
                }
            }
        }

        // all occupied: return deterministic initial
        return new int[]{idxLongInit, idxLatInit};
    }

    private static Vec3d getCarInteriorCenter(Vec3d carAnchor, Vec3d horizontalForward, Vec3d forward, double spacing, boolean reversed) {
        double halfSpacing = spacing / 2.0;
        double sign = reversed ? -1.0 : 1.0;
        return new Vec3d(
                carAnchor.x + horizontalForward.x * halfSpacing * sign,
                carAnchor.y + forward.y * halfSpacing * sign,
                carAnchor.z + horizontalForward.z * halfSpacing * sign
        );
    }

    private static boolean isTrainReversed(TrainClient trainClient) {
        if (trainClient == null) return false;

        String[] fieldNames = new String[]{"reversed", "isReversed", "reverse", "backwards"};
        for (String fieldName : fieldNames) {
            try {
                Field f = trainClient.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(trainClient);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {}

            try {
                Field f = trainClient.getClass().getField(fieldName);
                f.setAccessible(true);
                Object v = f.get(trainClient);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {}
        }

        String[] methodNames = new String[]{"isReversed", "getReversed", "isReverse", "isBackwards"};
        for (String methodName : methodNames) {
            try {
                Method m = trainClient.getClass().getMethod(methodName);
                Object v = m.invoke(trainClient);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {}
        }

        return false;
    }

    private static double safeGetDoubleField(TrainClient trainClient, String fieldName, double fallback) {
        if (trainClient == null || fieldName == null) return fallback;

        // 1) try declared field (private/protected)
        try {
            Field f = trainClient.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(trainClient);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        // 2) try public field
        try {
            Field f = trainClient.getClass().getField(fieldName);
            f.setAccessible(true);
            Object v = f.get(trainClient);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        // 3) try getter methods: getX(), isX()
        String capitalized = fieldName.length() > 0 ? Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) : fieldName;
        String[] getterNames = new String[] { "get" + capitalized, "is" + capitalized, fieldName };
        for (String mname : getterNames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }

        // 4) try common getters
        String[] altNames = new String[] { "getSpacing", "getWidth" };
        for (String mname : altNames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }

        return fallback;
    }

    // Smoothly move the passenger's displayed position toward a target to avoid teleporting.
    private static void applySmoothPosition(Passenger p, double targetX, double targetY, double targetZ, double factor) {
        if (Double.isNaN(p.x) || Double.isNaN(p.y) || Double.isNaN(p.z)) {
            p.x = targetX;
            p.y = targetY;
            p.z = targetZ;
            return;
        }
        double f = Math.max(0.05, Math.min(0.9, factor));
        p.x += (targetX - p.x) * f;
        p.y += (targetY - p.y) * f;
        p.z += (targetZ - p.z) * f;
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }
}
