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
            final double LONG_CELL = 0.65;
            final double LAT_CELL = 0.35;
            final double LONGITUDINAL_MARGIN = 0.45;
            final double LATERAL_MARGIN = 0.2;
            final double FLOOR_OFFSET = 0.95;

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
                                        double spacing = safeGetDoubleField(matchedTrain, "spacing", (double) matchedTrain.spacing);
                                        CarGeometry geometry = getCarGeometry(matchedTrain, i, spacing, new Vec3d(refX, refY, refZ));
                                        Vec3d carCenter = geometry.center();
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
                                double spacing = safeGetDoubleField(matchedTrain, "spacing", (double) matchedTrain.spacing);
                                Vec3d geometryReference = new Vec3d(passenger.x, passenger.y, passenger.z);
                                if (Double.isNaN(geometryReference.x) || Double.isNaN(geometryReference.y) || Double.isNaN(geometryReference.z)) {
                                    geometryReference = new Vec3d(
                                            Double.isNaN(passenger.boardingX) ? 0.0 : passenger.boardingX,
                                            Double.isNaN(passenger.boardingY) ? 0.0 : passenger.boardingY,
                                            Double.isNaN(passenger.boardingZ) ? 0.0 : passenger.boardingZ
                                    );
                                }
                                CarGeometry geometry = getCarGeometry(matchedTrain, carIdx, spacing, geometryReference);
                                Vec3d center = geometry.center();
                                Vec3d frameForward = geometry.forward();

                                if (frameForward == null) {
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
                                        frameForward = len == 0 ? new Vec3d(0, 0, 1) : new Vec3d(fx / len, fy / len, fz / len);
                                    } else {
                                        // additional fallback for single-car or missing yaw: try using vector to nearest other train
                                        Vec3d nearestOther = findNearestOtherTrainVector(matchedTrain, center);
                                        if (nearestOther != null) frameForward = nearestOther;
                                        else frameForward = new Vec3d(0, 0, 1);
                                    }
                                }

                                frameForward = normalizeOrDefault(frameForward, new Vec3d(0, 0, 1));
                                Vec3d worldUp = new Vec3d(0, 1, 0);
                                Vec3d frameRight = worldUp.crossProduct(frameForward);
                                frameRight = normalizeOrDefault(frameRight, new Vec3d(1, 0, 0));
                                Vec3d frameUp = normalizeOrDefault(frameForward.crossProduct(frameRight), worldUp);

                                // Yaw for visual rotation should still use horizontal heading.
                                Vec3d horizontalForward = normalizeOrDefault(new Vec3d(frameForward.x, 0.0, frameForward.z), new Vec3d(0, 0, 1));

                                // compute yaw/pitch for whole-body rotation based on horizontalForward and forward.y
                                double yawRad = Math.atan2(horizontalForward.x, horizontalForward.z);
                                double yawDeg = Math.toDegrees(yawRad);
                                double pitchDeg = Math.toDegrees(Math.asin(clamp(frameForward.y, -1.0, 1.0)));

                                // Decide left/right facing (per passenger)
                                boolean faceRight = ((passenger.id & 1L) == 0L);
                                computedYawDeg = yawDeg + (faceRight ? 90.0 : -90.0);
                                computedPitchDeg = pitchDeg;

                                // read width (reflection, with fallbacks)
                                double width = safeGetDoubleField(matchedTrain, "width", 2.0);

                                // Prepare frame occupancy for this train/car
                                frameOccupied.computeIfAbsent(matchedTrain.id, k -> new HashMap<>());
                                Map<Integer, Set<String>> trainOcc = frameOccupied.get(matchedTrain.id);
                                trainOcc.computeIfAbsent(carIdx, k -> new HashSet<>());

                                double usableLength = Math.max(LONG_CELL, spacing - LONGITUDINAL_MARGIN * 2.0);
                                int intSpacing = Math.max(1, (int) Math.floor(usableLength / LONG_CELL));
                                double usableWidth = Math.max(LAT_CELL, width - LATERAL_MARGIN * 2.0);
                                int intWidth = Math.max(1, (int)Math.floor(usableWidth / LAT_CELL));

                                // pick a cell with probing to avoid collisions
                                int[] chosen = pickAvailableCellIndex(passenger, matchedTrain, carIdx, intSpacing, intWidth, trainOcc.get(carIdx));
                                int idxLong = chosen[0];
                                int idxLat = chosen[1];

                                double startLong = -((intSpacing - 1) / 2.0) * LONG_CELL;
                                double startLat = -((intWidth - 1) / 2.0) * LAT_CELL;
                                double longitudinal = startLong + idxLong * LONG_CELL;
                                double lateralInt = startLat + idxLat * LAT_CELL;
                                double maxLongitudinal = Math.max(0.0, usableLength / 2.0 - 0.2);
                                double maxLateral = Math.max(0.0, usableWidth / 2.0 - 0.1);
                                longitudinal = clamp(longitudinal, -maxLongitudinal, maxLongitudinal);
                                lateralInt = clamp(lateralInt, -maxLateral, maxLateral);

                                Vec3d offset = frameForward.multiply(longitudinal).add(frameRight.multiply(lateralInt));

                                double targetX = center.x + offset.x + frameUp.x * FLOOR_OFFSET;
                                double targetY = center.y + offset.y + frameUp.y * FLOOR_OFFSET - 1.0;
                                double targetZ = center.z + offset.z + frameUp.z * FLOOR_OFFSET;

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
                                }
                            }

                            if (teleported) {
                                passenger.x += (Math.random() - 0.5) * 0.05;
                                passenger.z += (Math.random() - 0.5) * 0.05;
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
                    if (trainMatchesScheduledRoute(trainClient, passenger.scheduledRouteId)) {
                        return trainClient;
                    }
                } catch (Throwable ignored) {}
            }
            return null;
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

    private static boolean trainMatchesScheduledRoute(TrainClient trainClient, long scheduledRouteId) {
        long directRouteId = tryReadLong(trainClient, "routeId", "getRouteId");
        if (directRouteId != Long.MIN_VALUE) {
            return directRouteId == scheduledRouteId;
        }

        Object routeIdsObject = tryReadObject(trainClient, "routeIds", "getRouteIds");
        if (routeIdsObject instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Number number && number.longValue() == scheduledRouteId) {
                    return true;
                }
            }
        } else if (routeIdsObject != null && routeIdsObject.getClass().isArray()) {
            int length = Array.getLength(routeIdsObject);
            for (int i = 0; i < length; i++) {
                Object item = Array.get(routeIdsObject, i);
                if (item instanceof Number number && number.longValue() == scheduledRouteId) {
                    return true;
                }
            }
        }

        return false;
    }

    private static long tryReadLong(Object target, String fieldName, String getterName) {
        Object value = tryReadObject(target, fieldName, getterName);
        return value instanceof Number number ? number.longValue() : Long.MIN_VALUE;
    }

    private static Object tryReadObject(Object target, String fieldName, String getterName) {
        try {
            Field f = target.getClass().getField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {}
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {}
        try {
            Method m = target.getClass().getMethod(getterName);
            return m.invoke(target);
        } catch (Throwable ignored) {}
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

    private static Vec3d getCarInteriorCenter(Vec3d carAnchor, Vec3d frameForward, double spacing, Vec3d reference) {
        Vec3d halfCarOffset = frameForward.multiply(spacing * 0.5);
        Vec3d candidateA = carAnchor.add(halfCarOffset);
        Vec3d candidateB = carAnchor.subtract(halfCarOffset);
        if (reference == null) {
            return candidateA;
        }
        return candidateA.squaredDistanceTo(reference) <= candidateB.squaredDistanceTo(reference) ? candidateA : candidateB;
    }

    private static CarGeometry getCarGeometry(TrainClient trainClient, int carIndex, double spacing, Vec3d reference) {
        TrainCarPoseTracker.Pose trackedPose = TrainCarPoseTracker.get(trainClient.id, carIndex);
        if (trackedPose != null) {
            Vec3d trackedForward = normalizeOrDefault(trackedPose.forward(), new Vec3d(0, 0, 1));
            Vec3d trackedCenter = trackedPose.center();
            return new CarGeometry(trackedCenter, trackedCenter, trackedForward);
        }

        Vec3d anchor = ((TrainAccessor) trainClient).callGetRoutePosition(carIndex, trainClient.spacing);
        Vec3d forward = null;
        Double yawDeg = tryGetTrainYawDeg(trainClient, carIndex);
        Double pitchDeg = tryGetTrainPitchDeg(trainClient, carIndex);
        if (yawDeg != null) {
            double yawRad = Math.toRadians(yawDeg);
            double pitchRad = Math.toRadians(pitchDeg == null ? 0.0 : pitchDeg);
            double cosPitch = Math.cos(pitchRad);
            forward = new Vec3d(
                    Math.sin(yawRad) * cosPitch,
                    Math.sin(pitchRad),
                    Math.cos(yawRad) * cosPitch
            );
        }
        if (forward == null) {
            Vec3d oppositeAnchor = null;
            try {
                oppositeAnchor = ((TrainAccessor) trainClient).callGetRoutePosition(carIndex, -trainClient.spacing);
            } catch (Throwable ignored) {
            }
            if (oppositeAnchor != null) {
                Vec3d axis = oppositeAnchor.subtract(anchor);
                forward = normalizeOrDefault(axis, null);
            }
        }
        if (forward == null) {
            forward = computeCarForwardVector(trainClient, carIndex);
        }
        forward = normalizeOrDefault(forward, new Vec3d(0, 0, 1));
        Vec3d center = getCarInteriorCenter(anchor, forward, spacing, reference);

        return new CarGeometry(anchor, center, forward);
    }

    private static Vec3d normalizeOrDefault(Vec3d vector, Vec3d fallback) {
        if (vector == null) {
            return fallback;
        }
        double lengthSquared = vector.lengthSquared();
        if (lengthSquared < 1.0E-6) {
            return fallback;
        }
        double invLength = 1.0 / Math.sqrt(lengthSquared);
        return new Vec3d(vector.x * invLength, vector.y * invLength, vector.z * invLength);
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

    private record CarGeometry(Vec3d anchor, Vec3d center, Vec3d forward) {
    }
}
