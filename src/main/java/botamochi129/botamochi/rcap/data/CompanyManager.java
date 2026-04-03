package botamochi129.botamochi.rcap.data;

import botamochi129.botamochi.rcap.network.RcapServerPackets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentStateManager;

import java.util.ArrayList;
import java.util.List;

public class CompanyManager {
    public static List<Company> COMPANY_LIST = new ArrayList<>();

    private static CompanyState companyState;

    // 初期化（SERVER_STARTED 時）
    public static void init(ServerWorld world) {
        PersistentStateManager manager = world.getPersistentStateManager();

        companyState = manager.getOrCreate(
                CompanyState::createFromNbt,
                CompanyState::new,
                CompanyState.KEY
        );

        COMPANY_LIST = companyState.companyList; // 共有参照
    }

    // 会社を追加する時は必ず markDirty()
    public static void addCompany(Company company) {
        COMPANY_LIST.add(company);
        if (companyState != null) companyState.markDirty();
    }

    public static void save() {
        if (companyState != null) {
            companyState.markDirty(); // 保存フラグのみ設定
        }
    }

    public static Company getById(long id) {
        for (Company company : COMPANY_LIST) {
            if (company.id == id) return company;
        }
        return null;
    }

    public static void broadcastToAllPlayers(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            RcapServerPackets.sendCompanyList(player); // ← これが送信処理
        }
    }
}
