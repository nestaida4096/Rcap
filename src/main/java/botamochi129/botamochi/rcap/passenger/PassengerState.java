package botamochi129.botamochi.rcap.passenger;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

public class PassengerState extends PersistentState {
    public List<Passenger> passengerList = new ArrayList<>();
    public static final String KEY = "rcap_passengers";

    public PassengerState() {}

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Passenger p : passengerList) {
            list.add(p.toNbt());
        }
        nbt.put("Passengers", list);
        return nbt;
    }

    public static PassengerState createFromNbt(NbtCompound nbt) {
        PassengerState state = new PassengerState();
        if (nbt.contains("Passengers")) {
            NbtList list = nbt.getList("Passengers", 10); // 10 = TAG_Compound
            for (int i = 0; i < list.size(); i++) {
                state.passengerList.add(Passenger.fromNbt(list.getCompound(i)));
            }
        }
        return state;
    }
}
