package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.block.entity.OfficeBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class OfficeManager {
    // ★最適化：containsによるO(N)の重いスキャンを防ぎ、毎ティックの処理をO(1)にするため Set に変更
    private static final Set<OfficeBlockEntity> REGISTERED_OFFICES = new CopyOnWriteArraySet<>();

    public static void register(OfficeBlockEntity office) {
        // Setは重複を弾くので contains チェック不要で超高速
        REGISTERED_OFFICES.add(office);
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
        return new ArrayList<>(REGISTERED_OFFICES);
    }
}