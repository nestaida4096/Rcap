package botamochi129.botamochi.rcap.client.screen;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class HouseholdSliderWidget extends SliderWidget {
    private int valueInt;
    private static final int MIN = 1;
    private static final int MAX = 5;

    public HouseholdSliderWidget(int x, int y, int width, int height, int initialValue) {
        // 初期value（0.0〜1.0）に変換して渡す
        super(x, y, width, height, Text.of("人数: " + initialValue),
                (double)(initialValue - MIN) / (MAX - MIN));
        this.valueInt = initialValue;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        this.setMessage(Text.of("人数: " + valueInt));
    }

    @Override
    protected void applyValue() {
        // スナップ：小数→整数（四捨五入）
        int snapped = (int)Math.round(this.value * (MAX - MIN)) + MIN;

        // 範囲制限（念のため）
        if (snapped < MIN) snapped = MIN;
        if (snapped > MAX) snapped = MAX;

        // value自体も整数位置にスナップさせる（つまむ位置もピタッと合わせる）
        this.value = (double)(snapped - MIN) / (MAX - MIN);
        this.valueInt = snapped;
        updateMessage();
    }

    public int getValueInt() {
        return valueInt;
    }
}
