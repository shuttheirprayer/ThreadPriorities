package com.misanthropy.thread_priorities;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("thread_priorities")
public final class ThreadPriorities {
    public ThreadPriorities() {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener((ModConfigEvent event) -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                Config.apply();
            }
        });
        MinecraftForge.EVENT_BUS.addListener(ThreadPriorities::tick);
        Scanner.start();
    }

    private static boolean found;
    private static int renderLevel = Win.OFF;

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        boolean active = Minecraft.getInstance().isWindowActive();
        Scanner.active = active;
        int render = Scanner.render;
        int level = render == Win.OFF ? Win.OFF : Scanner.critical && active ? Win.TIME_CRITICAL : render;
        if (level != renderLevel) {
            Thread thread = Thread.currentThread();
            boolean lowered = renderLevel == Win.TIME_CRITICAL;
            renderLevel = level;
            if (level != Win.OFF && (lowered || thread.getPriority() < render)) {
                thread.setPriority(Math.max(thread.getPriority(), render));
                Scanner.LOGGER.info("render thread priority set to {}", thread.getPriority());
            }
            if (level == Win.TIME_CRITICAL && Win.current(Win.TIME_CRITICAL)) {
                Scanner.LOGGER.info("render thread priority set to time critical while the window is focused");
            }
        }
        if (found) {
            return;
        }
        found = true;
        try {
            Win.driverFunction(org.lwjgl.opengl.GL.getCapabilities().glBindFramebuffer);
        } catch (Throwable ignored) {
        }
    }
}
