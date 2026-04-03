package botamochi129.botamochi.rcap.data;

import mtr.data.NameColorDataBase;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;

import java.util.HashSet;
import java.util.Set;

public class Company extends NameColorDataBase {

    public final Set<Long> ownedRoutes = new HashSet<>();
    public final Set<Long> ownedDepots = new HashSet<>();

    public Company(long id, String name, int color) {
        super(id);
        this.name = name;
        this.color = color;
    }

    @Override
    protected boolean hasTransportMode() {
        return false;
    }

    public NbtCompound toNBT() {
        NbtCompound tag = new NbtCompound();
        tag.putLong("id", id);
        tag.putString("name", name);
        tag.putInt("color", color);

        NbtList routeList = new NbtList();
        for (long routeId : ownedRoutes) {
            routeList.add(NbtLong.of(routeId));
        }
        tag.put("ownedRoutes", routeList);

        NbtList depotList = new NbtList();
        for (long depotId : ownedDepots) {
            depotList.add(NbtLong.of(depotId));
        }
        tag.put("ownedDepots", depotList);

        return tag;
    }

    public static Company fromNBT(NbtCompound tag) {
        Company c = new Company(tag.getLong("id"), tag.getString("name"), tag.getInt("color"));

        if (tag.contains("ownedRoutes")) {
            NbtList routes = tag.getList("ownedRoutes", NbtElement.LONG_TYPE);
            for (int i = 0; i < routes.size(); i++) {
                c.ownedRoutes.add(((NbtLong) routes.get(i)).longValue());
            }
        }

        if (tag.contains("ownedDepots")) {
            NbtList depots = tag.getList("ownedDepots", NbtElement.LONG_TYPE);
            for (int i = 0; i < depots.size(); i++) {
                c.ownedDepots.add(((NbtLong) depots.get(i)).longValue());
            }
        }

        return c;
    }
}
