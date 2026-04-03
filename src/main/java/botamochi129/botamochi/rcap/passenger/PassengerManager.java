package botamochi129.botamochi.rcap.passenger;

import botamochi129.botamochi.rcap.network.RcapServerPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentStateManager;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

public class PassengerManager {
    // スレッドセーフなCopyOnWriteArrayListに変更
    public static List<Passenger> PASSENGER_LIST = new CopyOnWriteArrayList<>();
    private static PassengerState passengerState;

    public static final ConcurrentLinkedQueue<Passenger> PENDING_ADD_QUEUE = new ConcurrentLinkedQueue<>();

    public static void init(ServerWorld world) {
        PersistentStateManager manager = world.getPersistentStateManager();
        passengerState = manager.getOrCreate(
                PassengerState::createFromNbt, PassengerState::new, PassengerState.KEY
        );
        // passengerState.passengerListを現在のスレッドセーフなリストに置き換える処理
        if (passengerState.passengerList instanceof CopyOnWriteArrayList) {
            PASSENGER_LIST = passengerState.passengerList;
        } else {
            PASSENGER_LIST = new CopyOnWriteArrayList<>(passengerState.passengerList);
            passengerState.passengerList = PASSENGER_LIST;
        }
    }

    public static void save() {
        if (passengerState != null) passengerState.markDirty();
    }

    public static void broadcastToAllPlayers(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            RcapServerPackets.sendPassengerList(player);
        }
    }
}
