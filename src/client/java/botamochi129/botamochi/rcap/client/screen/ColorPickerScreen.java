package botamochi129.botamochi.rcap.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.function.Consumer;

public class ColorPickerScreen extends Screen {

    private final Screen parentScreen;
    private final int initialColor;
    private final Consumer<Integer> onConfirm;

    // HSVモデルで色を管理
    private float hue;        // 0.0 ~ 360.0
    private float saturation; // 0.0 ~ 1.0
    private float value;      // 0.0 ~ 1.0

    // UI配置・サイズ定数
    private static final int PALETTE_WIDTH = 180;
    private static final int PALETTE_HEIGHT = 120;
    private static final int HUE_BAR_HEIGHT = 12;
    private static final int PREVIEW_SIZE = 30;

    private int paletteX;
    private int paletteY;
    private int hueBarX;
    private int hueBarY;

    private boolean isDraggingPalette = false;
    private boolean isDraggingHue = false;

    public ColorPickerScreen(Screen parentScreen, int initialColor, Consumer<Integer> onConfirm) {
        super(Text.literal("カラーピッカー"));
        this.parentScreen = parentScreen;
        this.initialColor = initialColor & 0xFFFFFF;
        this.onConfirm = onConfirm;

        // RGBからHSVに逆変換
        float[] hsv = new float[3];
        rgbToHsv((this.initialColor >> 16) & 0xFF, (this.initialColor >> 8) & 0xFF, this.initialColor & 0xFF, hsv);
        this.hue = hsv[0];
        this.saturation = hsv[1];
        this.value = hsv[2];
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = Math.max(30, (this.height - 200) / 2);

        this.paletteX = centerX - PALETTE_WIDTH / 2;
        this.paletteY = startY + 15;

        this.hueBarX = paletteX;
        this.hueBarY = paletteY + PALETTE_HEIGHT + 10;

        // 保存・決定ボタン
        int buttonWidth = 80;
        int buttonHeight = 20;
        int buttonY = hueBarY + HUE_BAR_HEIGHT + 25;

        addDrawableChild(new ButtonWidget(centerX - buttonWidth - 4, buttonY, buttonWidth, buttonHeight, Text.literal("決定"), btn -> {
            onConfirm.accept(getRGB());
            MinecraftClient.getInstance().setScreen(parentScreen);
        }));

        addDrawableChild(new ButtonWidget(centerX + 4, buttonY, buttonWidth, buttonHeight, Text.literal("キャンセル"), btn -> {
            MinecraftClient.getInstance().setScreen(parentScreen);
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);

        // タイトル
        drawCenteredText(matrices, textRenderer, title, this.width / 2, 10, 0xFFFFFF);

        // 1. 彩度・明度(S・V)パレットの描画
        drawSaturationValuePalette(matrices);

        // 2. 色相(Hue)バーの描画
        drawHueBar(matrices);

        // ドラッグ処理のアップデート
        updateDragLogic(mouseX, mouseY);

        // 3. 現在のポインタ描画
        drawPointer(matrices);

        // 4. カラープレビュー & カラーコードのテキスト描画
        int currentRGB = getRGB();
        int previewX = paletteX + PALETTE_WIDTH + 15;
        int previewY = paletteY + 5;

        // プレビュー枠の背景
        fill(matrices, previewX - 1, previewY - 1, previewX + PREVIEW_SIZE + 1, previewY + PREVIEW_SIZE + 1, 0xFFFFFFFF);
        fill(matrices, previewX, previewY, previewX + PREVIEW_SIZE, previewY + PREVIEW_SIZE, currentRGB | 0xFF000000);

        // カラー詳細テキスト
        textRenderer.drawWithShadow(matrices, "選択中", previewX, previewY + PREVIEW_SIZE + 6, 0xAAAAAA);
        String hexStr = String.format("#%06X", currentRGB);
        textRenderer.drawWithShadow(matrices, Text.literal(hexStr).formatted(Formatting.GOLD), previewX, previewY + PREVIEW_SIZE + 18, 0xFFFFFF);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void drawSaturationValuePalette(MatrixStack matrices) {
        // 右上がバーの元色(S=1, V=1)、下が黒(V=0)、左上が白(S=0, V=1)
        // ピクセル単位で綺麗にグラデーションをブレンドして描画します
        int baseRGB = hsvToRgb(hue, 1.0f, 1.0f);

        for (int i = 0; i < PALETTE_HEIGHT; i++) {
            float v = 1.0f - ((float) i / PALETTE_HEIGHT); // 縦軸: 明度 (上ほど明るい)

            // 左端色 (白〜黒のグラデーション)
            int leftColor = hsvToRgb(hue, 0.0f, v);
            // 右端色 (色相の純色〜黒のグラデーション)
            int rightColor = hsvToRgb(hue, 1.0f, v);

            // 水平方向にグラデーションを描画
            fillGradient(matrices, paletteX, paletteY + i, paletteX + PALETTE_WIDTH, paletteY + i + 1, leftColor | 0xFF000000, rightColor | 0xFF000000);
        }

        // 外枠
        fill(matrices, paletteX - 1, paletteY - 1, paletteX, paletteY + PALETTE_HEIGHT + 1, 0xFF888888);
        fill(matrices, paletteX + PALETTE_WIDTH, paletteY - 1, paletteX + PALETTE_WIDTH + 1, paletteY + PALETTE_HEIGHT + 1, 0xFF888888);
        fill(matrices, paletteX - 1, paletteY - 1, paletteX + PALETTE_WIDTH + 1, paletteY, 0xFF888888);
        fill(matrices, paletteX - 1, paletteY + PALETTE_HEIGHT, paletteX + PALETTE_WIDTH + 1, paletteY + PALETTE_HEIGHT + 1, 0xFF888888);
    }

    private void drawHueBar(MatrixStack matrices) {
        // 色相バーを20分割して、虹色のグラデーションを滑らかに表現します
        int segments = 24;
        int segmentWidth = PALETTE_WIDTH / segments;

        for (int i = 0; i < segments; i++) {
            float h1 = ((float) i / segments) * 360.0f;
            float h2 = ((float) (i + 1) / segments) * 360.0f;
            int c1 = hsvToRgb(h1, 1.0f, 1.0f);
            int c2 = hsvToRgb(h2, 1.0f, 1.0f);

            int x1 = hueBarX + (i * segmentWidth);
            int x2 = (i == segments - 1) ? hueBarX + PALETTE_WIDTH : x1 + segmentWidth;

            fillGradient(matrices, x1, hueBarY, x2, hueBarY + HUE_BAR_HEIGHT, c1 | 0xFF000000, c2 | 0xFF000000);
        }

        // 外枠
        fill(matrices, hueBarX - 1, hueBarY - 1, hueBarX, hueBarY + HUE_BAR_HEIGHT + 1, 0xFF888888);
        fill(matrices, hueBarX + PALETTE_WIDTH, hueBarY - 1, hueBarX + PALETTE_WIDTH + 1, hueBarY + HUE_BAR_HEIGHT + 1, 0xFF888888);
        fill(matrices, hueBarX - 1, hueBarY - 1, hueBarX + PALETTE_WIDTH + 1, hueBarY, 0xFF888888);
        fill(matrices, hueBarX - 1, hueBarY + HUE_BAR_HEIGHT, hueBarX + PALETTE_WIDTH + 1, hueBarY + HUE_BAR_HEIGHT + 1, 0xFF888888);
    }

    private void drawPointer(MatrixStack matrices) {
        // パレット上の選択ポインタ (円形風の四角)
        int px = paletteX + (int) (saturation * PALETTE_WIDTH);
        int py = paletteY + (int) ((1.0f - value) * PALETTE_HEIGHT);

        fill(matrices, px - 2, py - 2, px + 3, py - 1, 0xFFFFFFFF);
        fill(matrices, px - 2, py + 2, px + 3, py + 3, 0xFFFFFFFF);
        fill(matrices, px - 2, py - 1, px - 1, py + 2, 0xFFFFFFFF);
        fill(matrices, px + 2, py - 1, px + 3, py + 2, 0xFFFFFFFF);

        fill(matrices, px - 1, py - 1, px + 2, py + 2, 0xFF000000);

        // 色相バー上の選択スライダー
        int hx = hueBarX + (int) ((hue / 360.0f) * PALETTE_WIDTH);
        fill(matrices, hx - 1, hueBarY - 2, hx + 2, hueBarY + HUE_BAR_HEIGHT + 2, 0xFFFFFFFF);
        fill(matrices, hx, hueBarY - 1, hx + 1, hueBarY + HUE_BAR_HEIGHT + 1, 0xFF000000);
    }

    private void updateDragLogic(double mouseX, double mouseY) {
        if (isDraggingPalette) {
            float s = (float) (mouseX - paletteX) / PALETTE_WIDTH;
            float v = 1.0f - ((float) (mouseY - paletteY) / PALETTE_HEIGHT);
            this.saturation = MathHelper.clamp(s, 0.0f, 1.0f);
            this.value = MathHelper.clamp(v, 0.0f, 1.0f);
        }
        if (isDraggingHue) {
            float h = (float) (mouseX - hueBarX) / PALETTE_WIDTH;
            this.hue = MathHelper.clamp(h * 360.0f, 0.0f, 360.0f);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (mouseX >= paletteX && mouseX <= paletteX + PALETTE_WIDTH &&
                    mouseY >= paletteY && mouseY <= paletteY + PALETTE_HEIGHT) {
                this.isDraggingPalette = true;
                updateDragLogic(mouseX, mouseY);
                return true;
            }
            if (mouseX >= hueBarX && mouseX <= hueBarX + PALETTE_WIDTH &&
                    mouseY >= hueBarY && mouseY <= hueBarY + HUE_BAR_HEIGHT) {
                this.isDraggingHue = true;
                updateDragLogic(mouseX, mouseY);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.isDraggingPalette = false;
            this.isDraggingHue = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int getRGB() {
        return hsvToRgb(hue, saturation, value);
    }

    // --- HSV変換ヘルパーメソッド群 ---

    public static int hsvToRgb(float h, float s, float v) {
        float r = 0, g = 0, b = 0;
        int i = (int) (h / 60) % 6;
        float f = (h / 60) - (int) (h / 60);
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);

        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            case 5 -> { r = v; g = p; b = q; }
        }

        int ri = MathHelper.clamp((int) (r * 255.0f), 0, 255);
        int gi = MathHelper.clamp((int) (g * 255.0f), 0, 255);
        int bi = MathHelper.clamp((int) (b * 255.0f), 0, 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    public static void rgbToHsv(int r, int g, int b, float[] hsv) {
        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;

        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float h = 0, s, v = max;

        float d = max - min;
        s = max == 0 ? 0 : d / max;

        if (max != min) {
            if (max == rf) {
                h = (gf - bf) / d + (gf < bf ? 6 : 0);
            } else if (max == gf) {
                h = (bf - rf) / d + 2;
            } else if (max == bf) {
                h = (rf - gf) / d + 4;
            }
            h /= 6;
        }

        hsv[0] = h * 360.0f;
        hsv[1] = s;
        hsv[2] = v;
    }
}