package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.client.network.ClientNetworking;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class ConfirmDeleteScreen extends ConfirmScreen {

    private final Company company;
    private final CompanyDashboardList dashboardList;
    private final Screen parent;

    public ConfirmDeleteScreen(Company company, CompanyDashboardList dashboardList, Screen parent) {
        super(
                confirmed -> {
                    if (confirmed) {
                        CompanyManager.COMPANY_LIST.removeIf(c -> c.id == company.id);
                        ClientNetworking.sendDeleteCompanyPacket(company.id);
                        CompanyManager.save(); // PersistentStateに保存
                        dashboardList.resetData(); // リスト更新
                    }
                    MinecraftClient.getInstance().setScreen(parent);
                },
                Text.literal("本当に削除しますか？"),
                Text.literal("会社: " + company.name)
        );
        this.company = company;
        this.dashboardList = dashboardList;
        this.parent = parent;
    }
}
