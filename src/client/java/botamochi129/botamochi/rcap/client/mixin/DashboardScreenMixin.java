package botamochi129.botamochi.rcap.client.mixin;

import botamochi129.botamochi.rcap.client.api.DashboardScreenExtensions;
import botamochi129.botamochi.rcap.client.screen.CompanyDashboardList;
import botamochi129.botamochi.rcap.client.screen.CompanyDashboardListWrapper;
import botamochi129.botamochi.rcap.client.screen.EditCompanyScreen;
import botamochi129.botamochi.rcap.data.Company;
import mtr.client.IDrawing;
import mtr.screen.DashboardScreen;
import mtr.screen.DashboardList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DashboardScreen.class)
public abstract class DashboardScreenMixin extends Screen implements DashboardScreenExtensions {

    private static final int BUTTON_WIDTH = 144;
    @Shadow @Final private ButtonWidget buttonTabStations;
    @Shadow @Final private ButtonWidget buttonTabRoutes;
    @Shadow @Final private ButtonWidget buttonTabDepots;

    @SuppressWarnings("invalid")
    @Shadow @Final private DashboardList dashboardList;

    @Unique private ButtonWidget buttonAddCompany;

    private boolean rcap_isCompanyTabSelected = false;
    private ButtonWidget buttonTabCompany;

    // 🎯 表示描画＆ロジックを分けて処理
    private CompanyDashboardList companyDashboardList;
    private CompanyDashboardListWrapper companyDashboardListWrapper;

    protected DashboardScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addCompanyTab(CallbackInfo ci) {
        final int PANEL_WIDTH = 144;
        final int tabWidth = PANEL_WIDTH / 4;
        final int tabHeight = 20;
        final int tabY = 0;

        IDrawing.setPositionAndWidth(buttonTabStations, 0, tabY, tabWidth);
        IDrawing.setPositionAndWidth(buttonTabRoutes, tabWidth, tabY, tabWidth);
        IDrawing.setPositionAndWidth(buttonTabDepots, tabWidth * 2, tabY, tabWidth);

        buttonTabCompany = new ButtonWidget(
                tabWidth * 3, tabY, tabWidth, tabHeight,
                Text.translatable("rcap.dashboard.company"),
                btn -> {
                    rcap_isCompanyTabSelected = true;
                    buttonTabStations.active = true;
                    buttonTabRoutes.active = true;
                    buttonTabDepots.active = true;
                    buttonTabCompany.active = false;
                }
        );
        addDrawableChild(buttonTabCompany);

        buttonAddCompany = new ButtonWidget(
                0,  // 路線と同じ右上レイアウト
                height - 20,                // 高さはMTRと合わせる
                BUTTON_WIDTH,
                20,
                Text.translatable("rcap.gui.add"),  // または Text.literal("+")
                btn -> {
                    // 編集画面へ
                    if (MinecraftClient.getInstance().currentScreen instanceof DashboardScreen screen) {
                        MinecraftClient.getInstance().setScreen(new EditCompanyScreen(screen, companyDashboardList, new Company(System.currentTimeMillis(), "", 0xFFFFFF)));
                    }
                }
        );
        buttonAddCompany.visible = false; // 初期は非表示
        addDrawableChild(buttonAddCompany);

        companyDashboardList = new CompanyDashboardList(this);
        companyDashboardList.setVisible(false);
        companyDashboardList.height = height - 40;


        // 👉 ラッパーを add ～ 系に渡す（これが Drawable & Element & Selectable ）
        companyDashboardListWrapper = new CompanyDashboardListWrapper(companyDashboardList, this.textRenderer);
        addDrawableChild(companyDashboardListWrapper);
        addSelectableChild(companyDashboardListWrapper);

        companyDashboardList.resetData();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void updateTabSelection(CallbackInfo ci) {
        if (!rcap_isCompanyTabSelected) return;

        if (!buttonTabStations.active || !buttonTabRoutes.active || !buttonTabDepots.active) {
            rcap_isCompanyTabSelected = false;
            if (buttonTabCompany != null) {
                buttonTabCompany.active = true;
            }

            if (companyDashboardList != null) {
                companyDashboardList.setVisible(false);
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void renderControl(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!rcap_isCompanyTabSelected) return;

        // 元のdashboardList無効化
        dashboardList.setData(java.util.List.of(), false, false, false, false, false, false);

        for (var widget : this.children()) {
            if (widget instanceof ButtonWidget w) {
                if (w != buttonTabStations && w != buttonTabRoutes && w != buttonTabDepots && w != buttonTabCompany) {
                    w.visible = false;
                }
            }
        }

        if (companyDashboardList != null) {
            companyDashboardList.setVisible(true);
            companyDashboardList.tick();
            companyDashboardList.renderCompanyList(matrices, textRenderer);
        }

        companyDashboardList.renderCompanyList(matrices, this.textRenderer);

        buttonAddCompany.visible = rcap_isCompanyTabSelected;
    }

    @Override
    public CompanyDashboardList getCompanyDashboardList() {
        return companyDashboardList;
    }
}
