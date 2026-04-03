package botamochi129.botamochi.rcap.client.mixin;

import mtr.screen.DashboardList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DashboardList.class)
public interface DashboardListAccessor {

    // ✅ private int page;
    @Accessor("page")
    int getPage();

    // ✅ private int itemsToShow()（これはメソッドなので Invoker）
    @Invoker("itemsToShow")
    int callItemsToShow();
}
