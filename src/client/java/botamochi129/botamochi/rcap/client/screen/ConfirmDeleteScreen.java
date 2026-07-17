package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.client.network.ClientNetworking;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ConfirmDeleteScreen extends ConfirmScreen {

    private final Company company;
    private final CompanyDashboardList dashboardList;
    private final Screen parent;

    public ConfirmDeleteScreen(Company company, CompanyDashboardList dashboardList, Screen parent) {
        super(
                confirmed -> {
                    if (confirmed) {
                        // 会社の削除処理と同期処理
                        CompanyManager.COMPANY_LIST.removeIf(c -> c.id == company.id);
                        ClientNetworking.sendDeleteCompanyPacket(company.id);
                        CompanyManager.save(); // PersistentStateにセーブデータを書き込み
                        dashboardList.resetData(); // ダッシュボードのリストを即時更新
                    }
                    MinecraftClient.getInstance().setScreen(parent);
                },
                // タイトルに警告色（赤色）を適用して、誤操作による削除を防ぎます
                Text.literal("本当にこの会社を削除しますか？").formatted(Formatting.RED, Formatting.BOLD),
                // 削除対象の会社名を明確に目立たせる装飾
                Text.literal("会社名: ").append(Text.literal(company.name).formatted(Formatting.YELLOW))
                        .append("\n\n")
                        .append(Text.literal("※この操作は取り消せません。").formatted(Formatting.GRAY))
        );
        this.company = company;
        this.dashboardList = dashboardList;
        this.parent = parent;
    }
}