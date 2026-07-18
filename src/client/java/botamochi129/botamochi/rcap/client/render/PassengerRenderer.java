package botamochi129.botamochi.rcap.client.render;

import botamochi129.botamochi.rcap.mixin.TrainAccessor;
import botamochi129.botamochi.rcap.passenger.Passenger;
import botamochi129.botamochi.rcap.passenger.PassengerManager;
import botamochi129.botamochi.rcap.passenger.TrainPassengerCountManager;
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

    private static final Map<Long, VisualState> visualStates = new HashMap<>();

    // ★修正2: 座席位置の一意な固定・ワープ防止用の堅牢なキャッシュ構造
    private static class AssignedSeat {
        final long trainId;
        final int carIndex;
        final int gridIndex;

        AssignedSeat(long trainId, int carIndex, int gridIndex) {
            this.trainId = trainId;
            this.carIndex = carIndex;
            this.gridIndex = gridIndex;
        }
    }

    // 乗客ID -> 割り当てられた固定座席のキャッシュ
    private static final Map<Long, AssignedSeat> passengerSeats = new HashMap<>();

    // 列車・車両のユニークキー (trainId_carIndex) -> 割り当て済み gridIndex のセット
    private static final Map<String, Set<Integer>> carAllocatedGrids = new HashMap<>();

    // ★修正3: 車端部に固まる現象を防ぐため、中央の席から優先的に並べた座席オーダーのキャッシュ
    private static final Map<Integer, int[]> seatOrderCache = new HashMap<>();

    private static class PositionSample {
        final double x, y, z;
        final long time;
        PositionSample(double x, double y, double z, long time) {
            this.x = x; this.y = y; this.z = z; this.time = time;
        }
    }

    private static class VisualState {
        double x, y, z;
        Passenger.MoveState lastMoveState = null;
        final List<PositionSample> history = new ArrayList<>();
        VisualState(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
        }
    }

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

            // 列車ごとに乗車している乗客人数を集計し、管理クラスに保存
            Map<Long, Integer> countsThisFrame = new HashMap<>();
            for (Passenger passenger : passengers) {
                if (passenger.moveState == Passenger.MoveState.ON_TRAIN && passenger.currentTrainId != null) {
                    countsThisFrame.merge(passenger.currentTrainId, 1, Integer::sum);
                }
            }
            TrainPassengerCountManager.clear();
            countsThisFrame.forEach(TrainPassengerCountManager::setCount);

            final double LONG_CELL = 0.65;
            final double LAT_CELL = 0.35;
            final double LONGITUDINAL_MARGIN = 0.45;
            final double LATERAL_MARGIN = 0.2;
            final double FLOOR_OFFSET = 0.95;

            Set<Long> activePassengerIds = new HashSet<>();

            for (Passenger passenger : passengers) {
                activePassengerIds.add(passenger.id);

                VisualState vs = visualStates.computeIfAbsent(passenger.id, k -> new VisualState(passenger.x, passenger.y, passenger.z));

                boolean wasOnTrain = (vs.lastMoveState == Passenger.MoveState.ON_TRAIN);
                boolean isOnTrain = (passenger.moveState == Passenger.MoveState.ON_TRAIN);
                if (wasOnTrain != isOnTrain) {
                    vs.history.clear();
                    vs.lastMoveState = passenger.moveState;
                } else if (vs.lastMoveState != passenger.moveState) {
                    vs.lastMoveState = passenger.moveState;
                }

                if (vs.history.isEmpty()) {
                    vs.history.add(new PositionSample(passenger.x, passenger.y, passenger.z, now));
                } else {
                    PositionSample lastSample = vs.history.get(vs.history.size() - 1);
                    if (lastSample.x != passenger.x || lastSample.y != passenger.y || lastSample.z != passenger.z) {
                        vs.history.add(new PositionSample(passenger.x, passenger.y, passenger.z, now));
                        if (vs.history.size() > 5) {
                            vs.history.remove(0);
                        }
                    }
                }

                Double computedYawDeg = null;
                Double computedPitchDeg = null;
                boolean renderSuccessOnTrain = false;

                if (passenger.moveState == Passenger.MoveState.ON_TRAIN) {
                    TrainClient matchedTrain = null;
                    if (passenger.currentTrainId != null) matchedTrain = findTrainClientById(passenger.currentTrainId);

                    if (matchedTrain == null && passenger.currentTrainId == null) {
                        matchedTrain = findTrainClientByScheduledRouteOrProximity(passenger);
                        if (matchedTrain != null) passenger.currentTrainId = matchedTrain.id;
                    }

                    if (matchedTrain != null) {
                        try {
                            int carCount = getTrainCarCount(matchedTrain);

                            // ★修正2: すでに座席が固定されている場合、強制的にキャッシュから号車を復元する（号車間ワープ防止）
                            AssignedSeat cachedSeat = passengerSeats.get(passenger.id);
                            if (cachedSeat != null && cachedSeat.trainId == matchedTrain.id) {
                                passenger.currentCarIndex = cachedSeat.carIndex;
                            }

                            if (passenger.currentCarIndex < 0) {
                                int bestIndex = -1;
                                double bestDist = Double.MAX_VALUE;
                                double refX = Double.isNaN(passenger.boardingX) ? passenger.x : passenger.boardingX;
                                double refY = Double.isNaN(passenger.boardingY) ? passenger.y : passenger.boardingY;
                                double refZ = Double.isNaN(passenger.boardingZ) ? passenger.z : passenger.boardingZ;
                                for (int i = 0; i < carCount; i++) {
                                    try {
                                        double spacing = safeGetDoubleField(matchedTrain, "spacing", (double) matchedTrain.spacing);
                                        CarGeometry geometry = getCarGeometry(matchedTrain, i, spacing, new Vec3d(refX, refY, refZ));

                                        if (geometry.center().lengthSquared() < 1.0) continue;

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
                                    if (carCount > 0) passenger.currentCarIndex = (int) (Math.floorMod(passenger.id, carCount));
                                }
                            }

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

                                if (center.lengthSquared() < 2.0) {
                                    throw new IllegalStateException("MTR Train Client Geometry is currently culled or unavailable.");
                                }

                                if (frameForward == null) {
                                    Double yawDegFb = tryGetTrainYawDeg(matchedTrain, carIdx);
                                    Double pitchDegFb = tryGetTrainPitchDeg(matchedTrain, carIdx);
                                    if (yawDegFb != null) {
                                        double yawRad = Math.toRadians(yawDegFb);
                                        double fz = Math.cos(yawRad);
                                        double fx = Math.sin(yawRad);
                                        double fy = 0.0;
                                        if (pitchDegFb != null) {
                                            fy = Math.sin(Math.toRadians(pitchDegFb));
                                        }
                                        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
                                        frameForward = len == 0 ? new Vec3d(0, 0, 1) : new Vec3d(fx / len, fy / len, fz / len);
                                    } else {
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

                                Vec3d horizontalForward = normalizeOrDefault(new Vec3d(frameForward.x, 0.0, frameForward.z), new Vec3d(0, 0, 1));

                                double yawRad = Math.atan2(horizontalForward.x, horizontalForward.z);
                                double yawDeg = Math.toDegrees(yawRad);
                                double pitchDeg = Math.toDegrees(Math.asin(clamp(frameForward.y, -1.0, 1.0)));

                                boolean faceRight = ((passenger.id & 1L) == 0L);
                                computedYawDeg = yawDeg + (faceRight ? 90.0 : -90.0);
                                computedPitchDeg = pitchDeg;

                                double width = safeGetDoubleField(matchedTrain, "width", 2.0);

                                double usableLength = Math.max(LONG_CELL, spacing - LONGITUDINAL_MARGIN * 2.0);
                                int intSpacing = Math.max(1, (int) Math.floor(usableLength / LONG_CELL));
                                double usableWidth = Math.max(LAT_CELL, width - LATERAL_MARGIN * 2.0);
                                int intWidth = Math.max(1, (int)Math.floor(usableWidth / LAT_CELL));

                                // ★修正2: 新たな座席の一意割り当て。一度決まればキャッシュにより絶対に動かない。
                                if (cachedSeat == null || cachedSeat.trainId != matchedTrain.id || cachedSeat.carIndex != carIdx) {
                                    String carKey = matchedTrain.id + "_" + carIdx;
                                    Set<Integer> allocated = carAllocatedGrids.computeIfAbsent(carKey, k -> new HashSet<>());

                                    int newGridIndex = 0;
                                    while (allocated.contains(newGridIndex)) {
                                        newGridIndex++;
                                    }
                                    allocated.add(newGridIndex);
                                    cachedSeat = new AssignedSeat(matchedTrain.id, carIdx, newGridIndex);
                                    passengerSeats.put(passenger.id, cachedSeat);
                                }

                                int gridIndex = cachedSeat.gridIndex;

                                // ★修正3: 車両の端から埋まる現象を防ぐため、中央から順に座席を割り当てる
                                int capacity = intSpacing * intWidth;
                                int assignedGridIndex = gridIndex;
                                if (capacity > 0) {
                                    int[] order = getSeatOrder(intSpacing, intWidth);
                                    if (gridIndex < capacity) {
                                        assignedGridIndex = order[gridIndex];
                                    } else {
                                        // 定員超過時は、適度に散らばるようにモジュロで折り返す
                                        assignedGridIndex = order[gridIndex % capacity];
                                    }
                                }

                                int idxLong = assignedGridIndex % intSpacing;
                                int idxLat = assignedGridIndex / intSpacing;

                                // 万が一の安全用クランプ
                                if (idxLat >= intWidth) {
                                    idxLat = intWidth - 1;
                                }
                                if (idxLong >= intSpacing) {
                                    idxLong = intSpacing - 1;
                                }

                                double startLong = -((intSpacing - 1) / 2.0) * LONG_CELL;
                                double startLat = -((intWidth - 1) / 2.0) * LAT_CELL;
                                double longitudinal = startLong + idxLong * LONG_CELL;
                                double lateralInt = startLat + idxLat * LAT_CELL;
                                double maxLongitudinal = Math.max(0.0, usableLength / 2.0 - 0.2);
                                double maxLateral = Math.max(0.0, usableWidth / 2.0 - 0.1);
                                longitudinal = clamp(longitudinal, -maxLongitudinal, maxLongitudinal);
                                lateralInt = clamp(lateralInt, -maxLateral, maxLateral);

                                Vec3d offset = frameForward.multiply(longitudinal).add(frameRight.multiply(lateralInt));

                                double floorOffset = getTrainRiderOffset(matchedTrain, FLOOR_OFFSET);
                                double targetX = center.x + offset.x + frameUp.x * floorOffset;
                                double targetY = center.y + offset.y + frameUp.y * floorOffset - 1.0;
                                double targetZ = center.z + offset.z + frameUp.z * floorOffset;

                                vs.x = targetX;
                                vs.y = targetY;
                                vs.z = targetZ;
                                renderSuccessOnTrain = true;
                            }
                        } catch (Throwable t) {
                            passenger.currentCarIndex = -1;
                            computedYawDeg = null;
                            computedPitchDeg = null;
                            renderSuccessOnTrain = false;
                        }
                    }
                }

                if (passenger.moveState == Passenger.MoveState.ON_TRAIN && !renderSuccessOnTrain) {
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
                        vs.x = bx + (ax - bx) * t;
                        vs.y = by + (ay - by) * t;
                        vs.z = bz + (az - bz) * t;
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
                                vs.x = bpPos.x + (apPos.x - bpPos.x) * t;
                                vs.y = bpPos.y + (apPos.y - bpPos.y) * t;
                                vs.z = bpPos.z + (apPos.z - bpPos.z) * t;
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
                            if (posRaw.lengthSquared() < 1.0) continue;

                            double dx = posRaw.x - vs.x;
                            double dy = posRaw.y - vs.y;
                            double dz = posRaw.z - vs.z;
                            double d2 = dx*dx + dy*dy + dz*dz;
                            if (d2 < best && d2 < 64 * 64) {
                                best = d2;
                                found = trainClient;
                            }
                        }
                        if (found != null) {
                            if (passenger.currentTrainId == null) {
                                passenger.currentTrainId = found.id;
                            }
                        } else {
                            boolean teleported = false;
                            if (!Double.isNaN(bx) && !Double.isNaN(by) && !Double.isNaN(bz) &&
                                    !Double.isNaN(ax) && !Double.isNaN(ay) && !Double.isNaN(az) && at > bt && bt > 0) {
                                double t = Math.min(1.0, Math.max(0.0, (double)(now - bt) / (double)(at - bt)));
                                if (t < 0.5) {
                                    vs.x = bx; vs.y = by; vs.z = bz;
                                } else {
                                    vs.x = ax; vs.y = ay; vs.z = az;
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
                                        vs.x = mid.getX() + 0.5; vs.y = mid.getY() + 1.0; vs.z = mid.getZ() + 0.5;
                                    } else {
                                        var mid = ap.getMidPos();
                                        vs.x = mid.getX() + 0.5; vs.y = mid.getY() + 1.0; vs.z = mid.getZ() + 0.5;
                                    }
                                    teleported = true;
                                } else if (bp != null) {
                                    var mid = bp.getMidPos();
                                    vs.x = mid.getX() + 0.5; vs.y = mid.getY() + 1.0; vs.z = mid.getZ() + 0.5;
                                    teleported = true;
                                } else if (ap != null) {
                                    var mid = ap.getMidPos();
                                    vs.x = mid.getX() + 0.5; vs.y = mid.getY() + 1.0; vs.z = mid.getZ() + 0.5;
                                    teleported = true;
                                }
                            }

                            if (teleported) {
                                vs.x += (Math.random() - 0.5) * 0.05;
                                vs.z += (Math.random() - 0.5) * 0.05;
                            }
                        }
                    }
                }

                if (passenger.moveState != Passenger.MoveState.ON_TRAIN) {
                    long renderTime = now - 1000;

                    if (vs.history.size() >= 2) {
                        PositionSample before = null;
                        PositionSample after = null;

                        for (int i = 0; i < vs.history.size() - 1; i++) {
                            PositionSample s1 = vs.history.get(i);
                            PositionSample s2 = vs.history.get(i + 1);
                            if (s1.time <= renderTime && renderTime <= s2.time) {
                                before = s1;
                                after = s2;
                                break;
                            }
                        }

                        if (before != null && after != null) {
                            double duration = after.time - before.time;
                            double factor = (duration > 0) ? (double) (renderTime - before.time) / duration : 1.0;
                            vs.x = before.x + (after.x - before.x) * factor;
                            vs.y = before.y + (after.y - before.y) * factor;
                            vs.z = before.z + (after.z - before.z) * factor;
                        } else if (renderTime > vs.history.get(vs.history.size() - 1).time) {
                            PositionSample latest = vs.history.get(vs.history.size() - 1);
                            double dx = latest.x - vs.x;
                            double dy = latest.y - vs.y;
                            double dz = latest.z - vs.z;
                            double distSq = dx * dx + dy * dy + dz * dz;
                            if (distSq > 10.0 * 10.0) {
                                vs.x = latest.x; vs.y = latest.y; vs.z = latest.z;
                            } else {
                                vs.x += dx * 0.15;
                                vs.y += dy * 0.15;
                                vs.z += dz * 0.15;
                            }
                        } else {
                            PositionSample oldest = vs.history.get(0);
                            vs.x = oldest.x; vs.y = oldest.y; vs.z = oldest.z;
                        }
                    } else {
                        double targetX = passenger.x;
                        double targetY = passenger.y;
                        double targetZ = passenger.z;

                        double dx = targetX - vs.x;
                        double dy = targetY - vs.y;
                        double dz = targetZ - vs.z;
                        double distSq = dx * dx + dy * dy + dz * dz;

                        if (distSq > 10.0 * 10.0) {
                            vs.x = targetX; vs.y = targetY; vs.z = targetZ;
                        } else {
                            vs.x += dx * 0.15;
                            vs.y += dy * 0.15;
                            vs.z += dz * 0.15;
                        }
                    }
                }

                double renderDx = vs.x - camera.getPos().x;
                double renderDy = vs.y - camera.getPos().y;
                double renderDz = vs.z - camera.getPos().z;

                if (renderDx * renderDx + renderDy * renderDy + renderDz * renderDz > 64 * 64) continue;

                BlockPos pos = new BlockPos(Math.floor(vs.x), Math.floor(vs.y), Math.floor(vs.z));
                int lightLevel = context.world().getLightLevel(pos);
                int light = LightmapTextureManager.pack(lightLevel, 0);

                matrices.push();
                matrices.translate(renderDx, renderDy + 1.5, renderDz);
                matrices.scale(-1f, -1f, 1f);

                if (computedYawDeg != null && computedPitchDeg != null) {
                    matrices.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion((computedYawDeg).floatValue()));
                    matrices.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion((computedPitchDeg).floatValue()));
                }

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

            visualStates.keySet().retainAll(activePassengerIds);

            // ★修正2: 電車内グリッド割り当てキャッシュのクリーンアップ（ON_TRAIN 状態でのみ座席を半永久保持する）
            Set<Long> onTrainPassengerIds = new HashSet<>();
            for (Passenger p : passengers) {
                if (p.moveState == Passenger.MoveState.ON_TRAIN) {
                    onTrainPassengerIds.add(p.id);
                }
            }

            // 降車した乗客の座席割り当て情報を破棄
            passengerSeats.keySet().retainAll(onTrainPassengerIds);

            // 車両ごとの割り当て済みインデックス一覧を再構築
            carAllocatedGrids.clear();
            for (AssignedSeat seat : passengerSeats.values()) {
                String carKey = seat.trainId + "_" + seat.carIndex;
                carAllocatedGrids.computeIfAbsent(carKey, k -> new HashSet<>()).add(seat.gridIndex);
            }
            // ========================================================
        });
    }

    // ★修正3: 座席の中央からの優先順位を計算してキャッシュするメソッド
    private static int[] getSeatOrder(int intSpacing, int intWidth) {
        int capacity = intSpacing * intWidth;
        if (capacity <= 0) return new int[0];

        return seatOrderCache.computeIfAbsent(capacity, c -> {
            Integer[] order = new Integer[c];
            for (int i = 0; i < c; i++) order[i] = i;

            // 車両の中心座標
            double midLong = (intSpacing - 1) / 2.0;
            double midLat = (intWidth - 1) / 2.0;

            // 中央に近い順にソートする
            Arrays.sort(order, (a, b) -> {
                int aLong = a % intSpacing;
                int aLat = a / intSpacing;
                int bLong = b % intSpacing;
                int bLat = b / intSpacing;

                double distA = Math.pow(aLong - midLong, 2) + Math.pow(aLat - midLat, 2);
                double distB = Math.pow(bLong - midLong, 2) + Math.pow(bLat - midLat, 2);

                if (distA == distB) {
                    return Integer.compare(a, b);
                }
                return Double.compare(distA, distB);
            });

            int[] result = new int[c];
            for(int i = 0; i < c; i++) result[i] = order[i];
            return result;
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
                if (posRaw.lengthSquared() < 1.0) continue;

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

    private static Vec3d findNearestOtherTrainVector(TrainClient self, Vec3d selfFront) {
        Vec3d bestVec = null;
        double bestDist = Double.MAX_VALUE;
        for (TrainClient tc : ClientData.TRAINS) {
            if (tc == null) continue;
            if (tc.id == self.id) continue;
            try {
                Vec3d otherFront = ((TrainAccessor) tc).callGetRoutePosition(0, tc.spacing);
                if (otherFront.lengthSquared() < 1.0) continue;

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

        try {
            Field f = trainClient.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(trainClient);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        try {
            Field f = trainClient.getClass().getField(fieldName);
            f.setAccessible(true);
            Object v = f.get(trainClient);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}

        String capitalized = fieldName.length() > 0 ? Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1) : fieldName;
        String[] getterNames = new String[] { "get" + capitalized, "is" + capitalized, fieldName };
        for (String mname : getterNames) {
            try {
                Method m = trainClient.getClass().getMethod(mname);
                Object v = m.invoke(trainClient);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (Throwable ignored) {}
        }

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

    private static double getTrainRiderOffset(TrainClient trainClient, double fallback) {
        Object properties = tryReadObject(trainClient, "trainProperties", "getTrainProperties");
        if (properties != null) {
            Object offsetObj = tryReadObject(properties, "riderOffset", "getRiderOffset");
            if (offsetObj instanceof Number number) {
                return number.doubleValue();
            }
        }
        return fallback;
    }

    private static double clamp(double v, double a, double b) {
        return v < a ? a : (v > b ? b : v);
    }

    private record CarGeometry(Vec3d anchor, Vec3d center, Vec3d forward) {
    }
}