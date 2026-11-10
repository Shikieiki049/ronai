package com.ronstrat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(RonStrategist.MODID)
public class RonStrategist {
    public static final String MODID = "ronstrat";
    public static final Logger LOG = LogUtils.getLogger();

    public RonStrategist() {
        MinecraftForge.EVENT_BUS.register(BotManager.class);
        MinecraftForge.EVENT_BUS.register(StratCommands.class);
        MinecraftForge.EVENT_BUS.register(AutoTest.class);
        MinecraftForge.EVENT_BUS.register(LobbyAi.class);
        MinecraftForge.EVENT_BUS.register(LobbySettings.class);
        Net.register();
    }
}
