package botamochi129.botamochi.rcap.passenger;

import botamochi129.botamochi.rcap.mixin.TrainAccessor;
import mtr.data.Train;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PassengerTrainStats {

    private PassengerTrainStats() {
    }

    public static int countPassengersOnTrain(long trainId) {
        int count = 0;
        for (Passenger passenger : PassengerManager.PASSENGER_LIST) {
            if (isPassengerOnTrain(passenger, trainId)) {
                count++;
            }
        }
        return count;
    }

    public static List<Passenger> getPassengersOnTrain(long trainId) {
        List<Passenger> passengers = new ArrayList<>();
        for (Passenger passenger : PassengerManager.PASSENGER_LIST) {
            if (isPassengerOnTrain(passenger, trainId)) {
                passengers.add(passenger);
            }
        }
        return passengers;
    }

    public static Map<Long, Integer> countPassengersByTrain() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Passenger passenger : PassengerManager.PASSENGER_LIST) {
            if (passenger == null || passenger.moveState != Passenger.MoveState.ON_TRAIN || passenger.currentTrainId == null) {
                continue;
            }
            counts.merge(passenger.currentTrainId, 1, Integer::sum);
        }
        return counts;
    }

    private static boolean isPassengerOnTrain(Passenger passenger, long trainId) {
        if (passenger != null && passenger.moveState == Passenger.MoveState.ON_TRAIN && passenger.currentTrainId == null) {
            Long resolvedTrainId = tryResolveClientTrainId(passenger);
            if (resolvedTrainId != null) {
                passenger.currentTrainId = resolvedTrainId;
            }
        }
        return passenger != null
                && passenger.moveState == Passenger.MoveState.ON_TRAIN
                && passenger.currentTrainId != null
                && passenger.currentTrainId == trainId;
    }

    private static Long tryResolveClientTrainId(Passenger passenger) {
        try {
            Class<?> clientDataClass = Class.forName("mtr.client.ClientData");
            Field trainsField = clientDataClass.getField("TRAINS");
            Object trainsObject = trainsField.get(null);
            if (!(trainsObject instanceof Iterable<?> iterable)) {
                return null;
            }

            Train bestTrain = null;
            double bestDistance = Double.MAX_VALUE;
            Vec3d passengerPos = new Vec3d(passenger.x, passenger.y, passenger.z);

            for (Object trainObject : iterable) {
                if (!(trainObject instanceof Train train)) {
                    continue;
                }
                if (passenger.scheduledRouteId != -1L && !trainMatchesScheduledRoute(trainObject, passenger.scheduledRouteId)) {
                    continue;
                }
                double distance = getBestDistanceSquared(train, passengerPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestTrain = train;
                }
            }

            return bestTrain == null ? null : bestTrain.id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double getBestDistanceSquared(Train train, Vec3d passengerPos) {
        int carCount = Math.max(1, getTrainCarCount(train));
        double bestDistance = Double.MAX_VALUE;
        for (int carIndex = 0; carIndex < carCount; carIndex++) {
            try {
                Vec3d carPos = ((TrainAccessor) train).callGetRoutePosition(carIndex, train.spacing);
                double distance = carPos.squaredDistanceTo(passengerPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                }
            } catch (Throwable ignored) {
            }
        }
        return bestDistance;
    }

    private static int getTrainCarCount(Train train) {
        try {
            Field field = train.getClass().getField("trainCars");
            Object value = field.get(train);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Field field = train.getClass().getDeclaredField("trainCars");
            field.setAccessible(true);
            Object value = field.get(train);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {
        }
        try {
            Method method = train.getClass().getMethod("getTrainCars");
            Object value = method.invoke(train);
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static boolean trainMatchesScheduledRoute(Object train, long scheduledRouteId) {
        long directRouteId = tryReadLong(train, "routeId", "getRouteId");
        if (directRouteId != Long.MIN_VALUE) {
            return directRouteId == scheduledRouteId;
        }

        Object routeIdsObject = tryReadObject(train, "routeIds", "getRouteIds");
        if (routeIdsObject instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Number number && number.longValue() == scheduledRouteId) {
                    return true;
                }
            }
        } else if (routeIdsObject instanceof Collection<?> collection) {
            for (Object item : collection) {
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
            Field field = target.getClass().getField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
        }
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
        }
        try {
            Method method = target.getClass().getMethod(getterName);
            return method.invoke(target);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
