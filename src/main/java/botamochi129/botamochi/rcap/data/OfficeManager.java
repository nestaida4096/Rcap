package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.OfficeBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OfficeManager {
    private static final List<OfficeBlockEntity> REGISTERED_OFFICES = new ArrayList<>();

    public static void register(OfficeBlockEntity office) {
        if (!REGISTERED_OFFICES.contains(office)) {
            REGISTERED_OFFICES.add(office);
        }
    }

    public static void unregister(OfficeBlockEntity office) {
        REGISTERED_OFFICES.remove(office);
    }

    public static OfficeBlockEntity getRandomAvailableOffice() {
        List<OfficeBlockEntity> available = REGISTERED_OFFICES.stream()
                .filter(OfficeBlockEntity::hasRoom)
                .toList();
        if (available.isEmpty()) return null;
        return available.get(new Random().nextInt(available.size()));
    }

    public static void clear() {
        REGISTERED_OFFICES.clear();
    }

    public static List<OfficeBlockEntity> getAll() {
        return List.copyOf(REGISTERED_OFFICES);
    }
}
