package botamochi129.botamochi.rcap.screen;

import botamochi129.botamochi.rcap.Rcap;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

public class ModScreens {
    public static ScreenHandlerType<HousingBlockScreenHandler> HOUSING_SCREEN_HANDLER;
    public static ScreenHandlerType<OfficeBlockScreenHandler> OFFICE_SCREEN_HANDLER;

    public static void registerScreenHandlers() {
        HOUSING_SCREEN_HANDLER = ScreenHandlerRegistry.registerExtended(
                new Identifier(Rcap.MOD_ID, "housing_screen"),
                (syncId, playerInventory, buf) ->
                        new HousingBlockScreenHandler(syncId, playerInventory, buf)
        );
        OFFICE_SCREEN_HANDLER = ScreenHandlerRegistry.registerExtended(
                new Identifier(Rcap.MOD_ID, "office_screen"),
                (syncId, playerInventory, buf) ->
                        new OfficeBlockScreenHandler(syncId, playerInventory, buf.readBlockPos(), buf.readInt())
        );
    }
}
