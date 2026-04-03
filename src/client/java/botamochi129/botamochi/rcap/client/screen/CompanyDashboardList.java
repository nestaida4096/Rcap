package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.client.api.DashboardScreenExtensions;
import botamochi129.botamochi.rcap.client.mixin.DashboardListAccessor;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import mtr.client.ClientData;
import mtr.data.NameColorDataBase;
import mtr.screen.DashboardList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;

import java.util.List;
import java.util.stream.Collectors;

public class CompanyDashboardList extends DashboardList {

    private final Screen parentScreen;
    private boolean visible = false;

    private final DashboardListAccessor accessor = (DashboardListAccessor) this;

    public CompanyDashboardList(Screen parentScreen) {
        super(
                (data, index) -> {
                    if (data instanceof Company company && parentScreen instanceof DashboardScreenExtensions ext) {
                        MinecraftClient.getInstance().setScreen(
                                new EditCompanyScreen(parentScreen, ext.getCompanyDashboardList(), company)
                        );
                    }
                },

                (data, index) -> {
                    if (data instanceof Company company && parentScreen instanceof DashboardScreenExtensions ext) {
                        MinecraftClient.getInstance().setScreen(
                                new EditCompanyScreen(parentScreen, ext.getCompanyDashboardList(), company)
                        );
                    }
                },

                (data, index) -> {
                    if (data instanceof Company company && parentScreen instanceof DashboardScreenExtensions ext) {
                        MinecraftClient.getInstance().setScreen(
                                new EditCompanyScreen(parentScreen, ext.getCompanyDashboardList(), company)
                        );
                    }
                },

                () -> {
                },   // onSort
                null,       // onAdd
                null,       // onDelete は描画・クリックで処理
                () -> CompanyManager.COMPANY_LIST.stream().map(c -> (NameColorDataBase) c).collect(Collectors.toList()),
                () -> ClientData.DASHBOARD_SEARCH,
                s -> ClientData.DASHBOARD_SEARCH = s
        );

        this.parentScreen = parentScreen;
        this.x = 0;
        this.y = 20;
        this.width = 144;
        this.height = MinecraftClient.getInstance().getWindow().getScaledHeight() - 32;

        resetData();
    }

    public void resetData() {
        setData(
                CompanyManager.COMPANY_LIST.stream()
                        .map(c -> (NameColorDataBase) c)
                        .collect(Collectors.toList()),
                false, true, true, false, false, true
        );
    }

    /**
     * 描画：リスト本体 + 削除ボタン
     **/
    public void renderCompanyList(MatrixStack matrices, TextRenderer font) {
        if (visible) {
            super.render(matrices, font);
            renderExtras(matrices, font);
        }
    }

    private void renderExtras(MatrixStack matrices, TextRenderer font) {
        int indexOffset = accessor.getPage() * accessor.callItemsToShow();
        int itemsToDraw = (height - 24) / 20;

        List<Company> viewList = CompanyManager.COMPANY_LIST.stream()
                .skip(indexOffset)
                .limit(itemsToDraw)
                .collect(Collectors.toList());

        for (int i = 0; i < viewList.size(); i++) {
            Company company = viewList.get(i);
            int drawY = y + 6 + 24 + 20 * i;

            font.drawWithShadow(matrices, "路線: " + company.ownedRoutes.size(), x + 8, drawY + 10, 0xAAAAAA);
            font.drawWithShadow(matrices, "車庫: " + company.ownedDepots.size(), x + 50, drawY + 10, 0xAAAAAA); // ← 車庫も追加！

            // ✎ 編集ボタン
            int editX = x + width - 32;
            DrawableHelper.fill(matrices, editX, drawY + 2, editX + 12, drawY + 14, 0xFF0080FF);
            font.drawWithShadow(matrices, "✎", editX + 2, drawY + 4, 0xFFFFFF);

            // ✕ 削除ボタン
            int deleteX = x + width - 16;
            DrawableHelper.fill(matrices, deleteX, drawY + 2, deleteX + 12, drawY + 14, 0xFFAA0000);
            font.drawWithShadow(matrices, "✕", deleteX + 2, drawY + 4, 0xFFFFFF);
        }
    }

    public boolean handleCompanyClick(double mouseX, double mouseY, int button) {
        if (!visible) return false;

        int indexOffset = accessor.getPage() * accessor.callItemsToShow();
        int itemsToShow = (height - 24) / 20;

        List<Company> viewList = CompanyManager.COMPANY_LIST.stream()
                .skip(indexOffset)
                .limit(itemsToShow)
                .collect(Collectors.toList());

        for (int i = 0; i < viewList.size(); i++) {
            Company company = viewList.get(i);
            int drawY = y + 6 + 24 + 20 * i;
            int buttonY = drawY + 2;

            int editX = x + width - 32;
            int deleteX = x + width - 16;

            if (mouseX >= editX && mouseX <= editX + 12 &&
                    mouseY >= buttonY && mouseY <= buttonY + 14) {
                MinecraftClient.getInstance().setScreen(
                        new EditCompanyScreen(parentScreen, this, company)
                );
                return true;
            }

            if (mouseX >= deleteX && mouseX <= deleteX + 12 &&
                    mouseY >= buttonY && mouseY <= buttonY + 14) {
                MinecraftClient.getInstance().setScreen(
                        new ConfirmDeleteScreen(company, this, parentScreen)
                );
                return true;
            }
        }

        return false;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible;
    }
}
