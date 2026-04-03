package botamochi129.botamochi.rcap.client.screen;

import botamochi129.botamochi.rcap.client.network.ClientNetworking;
import botamochi129.botamochi.rcap.data.Company;
import botamochi129.botamochi.rcap.data.CompanyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class EditCompanyScreen extends Screen {

    private final Screen parent;
    private final CompanyDashboardList dashboardList;
    private final Company company;

    private TextFieldWidget nameField;
    private ButtonWidget colorButton;

    private final List<Long> selectedRouteIds = new ArrayList<>();
    private final List<Long> selectedDepotIds = new ArrayList<>();

    private ButtonWidget routeButton;
    private ButtonWidget depotButton;

    private boolean initialized = false;

    public EditCompanyScreen(Screen parent, CompanyDashboardList dashboardList, Company company) {
        super(Text.literal("会社編集"));
        this.parent = parent;
        this.dashboardList = dashboardList;
        this.company = company;
    }

    @Override
    protected void init() {
        if (!initialized) {
            selectedRouteIds.clear();
            selectedRouteIds.addAll(company.ownedRoutes);

            selectedDepotIds.clear();
            selectedDepotIds.addAll(company.ownedDepots);

            initialized = true; // ✅ もう初期化しないように
        }

        final int centerX = width / 2;
        final int startY = height / 4;

        nameField = new TextFieldWidget(textRenderer, centerX - 100, startY, 200, 20, Text.literal("会社名"));
        nameField.setText(company.name);
        addDrawableChild(nameField);
        addSelectableChild(nameField);

        colorButton = new ButtonWidget(centerX - 100, startY + 30, 200, 20, getColorLabel(company.color), button -> {
            company.color = nextColor(company.color);
            colorButton.setMessage(getColorLabel(company.color));
        });
        addDrawableChild(colorButton);

        routeButton = new ButtonWidget(centerX - 100, startY + 60, 200, 20, getRouteLabel(), btn -> {
            MinecraftClient.getInstance().setScreen(
                    new RouteDepotSelectScreen(this, selectedRouteIds, true, selected -> {
                        System.out.println("[RCAP] EditCompanyScreen: route 選択結果: " + selected);

                        selectedRouteIds.clear();
                        selectedRouteIds.addAll(selected);

                        // ✅ 最新化された件数表示も確認
                        System.out.println("[RCAP] 更新後 route ids: " + selectedRouteIds);
                        routeButton.setMessage(getRouteLabel());
                    })
            );
        });
        addDrawableChild(routeButton);

        depotButton = new ButtonWidget(centerX - 100, startY + 90, 200, 20, getDepotLabel(), btn -> {
            MinecraftClient.getInstance().setScreen(
                    new RouteDepotSelectScreen(this, selectedDepotIds, false, selected -> {
                        System.out.println("[RCAP] EditCompanyScreen: depot 選択結果: " + selected);

                        selectedDepotIds.clear();
                        selectedDepotIds.addAll(selected);

                        System.out.println("[RCAP] 更新後 depot ids: " + selectedDepotIds);
                        depotButton.setMessage(getDepotLabel());
                    })
            );
        });
        addDrawableChild(depotButton);

        addDrawableChild(new ButtonWidget(centerX - 100, startY + 120, 98, 20, Text.literal("保存"), button -> {
            company.name = nameField.getText().trim();

            // 🔍 保存に使われるIDを確認
            System.out.println("[RCAP] 保存前 route: " + selectedRouteIds);
            System.out.println("[RCAP] 保存前 depot: " + selectedDepotIds);

            company.ownedRoutes.clear();
            company.ownedRoutes.addAll(selectedRouteIds);

            company.ownedDepots.clear();
            company.ownedDepots.addAll(selectedDepotIds);

            if (!CompanyManager.COMPANY_LIST.contains(company)) {
                CompanyManager.COMPANY_LIST.add(company);
                ClientNetworking.sendCreateCompanyPacket(company); // ✅ 新規作成の場合のみ送信
            } else {
                ClientNetworking.sendUpdateCompanyPacket(company); // ✅ 既存なら更新を送信
            }
            CompanyManager.save();

            dashboardList.resetData();

            MinecraftClient.getInstance().setScreen(parent);
        }));

        addDrawableChild(new ButtonWidget(centerX + 2, startY + 120, 98, 20, Text.literal("キャンセル"), button -> {
            MinecraftClient.getInstance().setScreen(parent);
        }));
    }

    private Text getColorLabel(int color) {
        return Text.literal(String.format("色: #%06X", color & 0xFFFFFF));
    }

    private Text getRouteLabel() {
        return Text.literal("所有路線: " + selectedRouteIds.size() + "件");
    }

    private Text getDepotLabel() {
        return Text.literal("所有車庫: " + selectedDepotIds.size() + "件");
    }

    private int nextColor(int current) {
        int[] presets = { 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0xFFFF00 };
        for (int i = 0; i < presets.length; i++) {
            if ((current & 0xFFFFFF) == presets[i]) {
                return presets[(i + 1) % presets.length];
            }
        }
        return presets[0];
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        drawCenteredText(matrices, textRenderer, title, width / 2, 20, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }
}
