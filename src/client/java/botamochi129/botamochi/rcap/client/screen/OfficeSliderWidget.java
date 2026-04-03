package botamochi129.botamochi.rcap.client.screen;

import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class OfficeSliderWidget extends SliderWidget {
    private int valueInt;
    private static final int MIN = 1;
    private static final int MAX = 1000;

    public OfficeSliderWidget(int x, int y, int width, int height, int initialValue) {
        super(x, y, width, height, Text.of("スタッフ数: " + initialValue),
                (double)(initialValue - MIN) / (MAX - MIN));
        this.valueInt = initialValue;
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal("スタッフ数: " + valueInt));
    }

    @Override
    protected void applyValue() {
        // valueは0.0～1.0の小数なので、整数範囲へのスナップ
        int snapped = (int)Math.round(value * (MAX - MIN)) + MIN;
        if (snapped < MIN) snapped = MIN;
        if (snapped > MAX) snapped = MAX;
        value = (double)(snapped - MIN) / (MAX - MIN);  // ここでつまむ位置も整数値にスナップ
        valueInt = snapped;
        updateMessage();
    }

    public int getValueInt() { return valueInt; }
}
