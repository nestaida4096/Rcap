package botamochi129.botamochi.rcap.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.List;

public class CompanyState extends PersistentState {

    public final List<Company> companyList = new ArrayList<>();

    public static final String KEY = "rcap_companies";

    public CompanyState() {}

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Company c : companyList) {
            list.add(c.toNBT());
        }
        nbt.put("Companies", list);
        return nbt;
    }

    public static CompanyState createFromNbt(NbtCompound nbt) {
        CompanyState state = new CompanyState();
        if (nbt.contains("Companies", NbtCompound.LIST_TYPE)) {
            NbtList list = nbt.getList("Companies", NbtCompound.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                state.companyList.add(Company.fromNBT(list.getCompound(i)));
            }
        }
        return state;
    }
}
