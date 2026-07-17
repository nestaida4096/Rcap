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
import net.minecraft.util.Formatting;

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

    // 💡 レスポンシブ設計用の定数
    private static final int MAX_WIDGET_WIDTH = 200;
    private static final int WIDGET_HEIGHT = 20;
    private static final int SPACING = 24; // 要素間の垂直間隔

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

            initialized = true; // 初回移行の初期化リセット防止
        }

        final int centerX = this.width / 2;

        // 📌 レスポンシブ対応のパーツ横幅算出 (画面が狭いときにはみ出さないようマージンを確保)
        final int widgetWidth = Math.min(MAX_WIDGET_WIDTH, this.width - 40);

        // 📌 画面の高さに応じて開始位置を動的に最適化（ボタンが画面外にはみ出るのを防ぐ）
        // パーツ全体の高さ: テキストフィールド(20) + 隙間x4(96) + ボタン(20) = 約140px
        final int totalContentHeight = WIDGET_HEIGHT + (SPACING * 4) + WIDGET_HEIGHT;
        final int startY = Math.max(40, (this.height - totalContentHeight) / 2);

        // 1. 会社名テキストフィールド
        nameField = new TextFieldWidget(textRenderer, centerX - widgetWidth / 2, startY, widgetWidth, WIDGET_HEIGHT, Text.literal("会社名"));
        nameField.setText(company.name);
        addDrawableChild(nameField);
        addSelectableChild(nameField);

        // 2. コーポレートカラー自由変更ボタン (ここから新しいカラーピッカーへジャンプします)
        colorButton = new ButtonWidget(centerX - widgetWidth / 2, startY + SPACING, widgetWidth, WIDGET_HEIGHT, getColorLabel(company.color), button -> {
            MinecraftClient.getInstance().setScreen(new ColorPickerScreen(this, company.color, newColor -> {
                company.color = newColor;
                colorButton.setMessage(getColorLabel(newColor));
            }));
        });
        addDrawableChild(colorButton);

        // 3. 所有路線選択への遷移ボタン
        routeButton = new ButtonWidget(centerX - widgetWidth / 2, startY + (SPACING * 2), widgetWidth, WIDGET_HEIGHT, getRouteLabel(), btn -> {
            MinecraftClient.getInstance().setScreen(
                    new RouteDepotSelectScreen(this, selectedRouteIds, true, selected -> {
                        System.out.println("[RCAP] EditCompanyScreen: route 選択結果: " + selected);
                        selectedRouteIds.clear();
                        selectedRouteIds.addAll(selected);
                        System.out.println("[RCAP] 更新後 route ids: " + selectedRouteIds);
                        routeButton.setMessage(getRouteLabel());
                    })
            );
        });
        addDrawableChild(routeButton);

        // 4. 所有車庫選択への遷移ボタン
        depotButton = new ButtonWidget(centerX - widgetWidth / 2, startY + (SPACING * 3), widgetWidth, WIDGET_HEIGHT, getDepotLabel(), btn -> {
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

        // 5. 下部アクションボタン（保存 ＆ キャンセル）
        final int actionButtonWidth = (widgetWidth - 4) / 2; // ボタンを隙間4pxで左右均等に分ける
        final int actionButtonY = startY + (SPACING * 4);

        // 保存ボタン
        addDrawableChild(new ButtonWidget(centerX - widgetWidth / 2, actionButtonY, actionButtonWidth, WIDGET_HEIGHT, Text.literal("保存"), button -> {
            company.name = nameField.getText().trim();

            System.out.println("[RCAP] 保存前 route: " + selectedRouteIds);
            System.out.println("[RCAP] 保存前 depot: " + selectedDepotIds);

            company.ownedRoutes.clear();
            company.ownedRoutes.addAll(selectedRouteIds);

            company.ownedDepots.clear();
            company.ownedDepots.addAll(selectedDepotIds);

            if (!CompanyManager.COMPANY_LIST.contains(company)) {
                CompanyManager.COMPANY_LIST.add(company);
                ClientNetworking.sendCreateCompanyPacket(company);
            } else {
                ClientNetworking.sendUpdateCompanyPacket(company);
            }
            CompanyManager.save();

            dashboardList.resetData();

            MinecraftClient.getInstance().setScreen(parent);
        }));

        // キャンセルボタン
        addDrawableChild(new ButtonWidget(centerX + 2, actionButtonY, actionButtonWidth, WIDGET_HEIGHT, Text.literal("キャンセル"), button -> {
            MinecraftClient.getInstance().setScreen(parent);
        }));
    }

    private Text getColorLabel(int color) {
        String hex = String.format("#%06X", color & 0xFFFFFF);
        // カラーラベルの視認性を向上させるテキスト装飾
        return Text.literal("コーポレートカラー: ").append(Text.literal(hex).formatted(Formatting.GOLD));
    }

    private Text getRouteLabel() {
        return Text.literal("所有路線設定 (" + selectedRouteIds.size() + "件)");
    }

    private Text getDepotLabel() {
        return Text.literal("所有車庫設定 (" + selectedDepotIds.size() + "件)");
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // 会社編集のタイトルの影付き中央描画
        drawCenteredText(matrices, textRenderer, title, width / 2, 15, 0xFFFFFF);

        super.render(matrices, mouseX, mouseY, delta);
    }
}