package botamochi129.botamochi.rcap.client;

import botamochi129.botamochi.rcap.client.network.RcapClientPackets;
import botamochi129.botamochi.rcap.client.render.PassengerRenderer;
import botamochi129.botamochi.rcap.client.screen.HousingBlockScreen;
import botamochi129.botamochi.rcap.client.screen.OfficeBlockScreen;
import botamochi129.botamochi.rcap.screen.ModScreens;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screenhandler.v1.ScreenRegistry;

public class RcapClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        RcapClientPackets.register();

        ScreenRegistry.register(ModScreens.HOUSING_SCREEN_HANDLER, HousingBlockScreen::new);
        ScreenRegistry.register(ModScreens.OFFICE_SCREEN_HANDLER, OfficeBlockScreen::new);

        PassengerRenderer.register();
    }
}
