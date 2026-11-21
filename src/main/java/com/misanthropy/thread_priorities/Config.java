package com.misanthropy.thread_priorities;

import net.minecraftforge.common.ForgeConfigSpec;

final class Config {
    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue DRIVER;
    static final ForgeConfigSpec.BooleanValue BACKGROUND;
    static final ForgeConfigSpec.BooleanValue RENDER;
    static final ForgeConfigSpec.BooleanValue FOCUSED;
    static final ForgeConfigSpec.BooleanValue POOL;
    static final ForgeConfigSpec.BooleanValue LOG;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        DRIVER = b.comment("The worker threads of the graphics driver.")
                .define("driverThreads", true);
        BACKGROUND = b.comment("Java's compiler and background garbage collector threads run behind.")
                .define("backgroundThreads", true);
        RENDER = b.comment("The render thread runs at the highest priority on every PC.")
                .define("renderThread", true);
        FOCUSED = b.comment("While the game window is focused, the render thread, the graphics driver's threads and the input and sound threads run at the top priority Windows gives a normal program.")
                .define("topPriorityWhileFocused", true);
        POOL = b.comment("Threads of Java's shared worker pool (parallel streams and async tasks from mods) run below normal priority.")
                .define("sharedPool", false);
        LOG = b.comment("Log what was changed once a minute.")
                .define("log", false);
        SPEC = b.build();
    }

    private Config() {
    }

    static void apply() {
        Scanner.driver = Scanner.override("driver", DRIVER.get() ? Win.HIGHEST : Win.OFF);
        Scanner.background = Scanner.override("background", BACKGROUND.get() ? 1 : Win.OFF);
        Scanner.render = Scanner.override("render", RENDER.get() ? Thread.MAX_PRIORITY : Win.OFF);
        Scanner.critical = Scanner.critical(FOCUSED.get());
        Scanner.pool = Scanner.override("pool", POOL.get() ? 3 : Win.OFF);
        Scanner.log = LOG.get() || Boolean.getBoolean("thread_priorities.log");
    }
}
