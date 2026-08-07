package com.stellar.core;

import com.stellar.core.config.StellarConfig;
import com.stellar.core.logic.ChunkStateManager;
import com.stellar.core.logic.LazyEntityAI;
import com.stellar.core.logic.LazyRedstone;
import com.stellar.core.render.ChunkRenderCache;
import com.stellar.core.render.DynamicLOD;
import com.stellar.core.render.OctreeFrustumCuller;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

public class StellarCore implements ModInitializer {

    public static final String MOD_ID = "stellar-core";
    public static final String MOD_NAME = "Stellar Core Engine";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static StellarCore instance;

    private StellarConfig config;
    private ChunkRenderCache renderCache;
    private OctreeFrustumCuller frustumCuller;
    private DynamicLOD dynamicLOD;
    private ChunkStateManager chunkStateManager;
    private LazyEntityAI lazyEntityAI;
    private LazyRedstone lazyRedstone;

    private long totalCachedFrames = 0;
    private long totalCulledChunks = 0;
    private long totalFrozenChunks = 0;
    private long totalLazyEntities = 0;
    private long totalRedstoneSnapshots = 0;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("============================================");
        LOGGER.info("  {} v{} 初始化中...", MOD_NAME, "1.0.0");
        LOGGER.info("  三层递进优化架构已激活");
        LOGGER.info("============================================");

        this.config = StellarConfig.load();

        LOGGER.info("[L1 渲染层] 初始化区块渲染缓存...");
        this.renderCache = new ChunkRenderCache(config);
        LOGGER.info("[L1 渲染层] 初始化八叉树视锥剔除...");
        this.frustumCuller = new OctreeFrustumCuller(config);
        LOGGER.info("[L1 渲染层] 初始化动态 LOD 系统...");
        this.dynamicLOD = new DynamicLOD(config);
        LOGGER.info("[L1 渲染层] 初始化完成。");

        LOGGER.info("[L3 逻辑层] 初始化区块状态机...");
        this.chunkStateManager = new ChunkStateManager(config);
        LOGGER.info("[L3 逻辑层] 初始化实体 AI 惰性化引擎...");
        this.lazyEntityAI = new LazyEntityAI(config);
        LOGGER.info("[L3 逻辑层] 初始化红石惰性化引擎...");
        this.lazyRedstone = new LazyRedstone(config);
        LOGGER.info("[L3 逻辑层] 初始化完成。");

        registerCommands();
        registerEvents();

        LOGGER.info("============================================");
        LOGGER.info("  {} 初始化完成！性能提升已激活。", MOD_NAME);
        LOGGER.info("  输入 /stellarcore stats 查看优化统计");
        LOGGER.info("============================================");
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("stellarcore")
                    .then(literal("stats")
                        .executes(context -> {
                            displayStats(context.getSource().getServer());
                            return 1;
                        })
                    )
                    .then(literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> {
                            reloadConfig();
                            context.getSource().sendFeedback(
                                () -> Text.literal("§a[StellarCore] 配置已重新加载。"), false
                            );
                            return 1;
                        })
                    )
                    .then(literal("help")
                        .executes(context -> {
                            displayHelp(context.getSource().getServer());
                            return 1;
                        })
                    )
            );
        });
    }

    private void registerEvents() {
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        if (server.getTicks() % 20 == 0) {
            if (chunkStateManager != null) {
                totalFrozenChunks = chunkStateManager.getFrozenChunkCount();
            }
            if (lazyEntityAI != null) {
                totalLazyEntities = lazyEntityAI.getLazyEntityCount();
            }
            if (lazyRedstone != null) {
                totalRedstoneSnapshots = lazyRedstone.getSnapshotCount();
            }
        }
    }

    private void displayStats(MinecraftServer server) {
        server.execute(() -> {
            LOGGER.info("========== Stellar Core 优化统计 ==========");
            LOGGER.info("[L1 渲染层]");
            LOGGER.info("  缓存命中率: {} / {} ({}%)",
                renderCache.getCacheHits(),
                renderCache.getTotalRequests(),
                renderCache.getHitRate()
            );
            LOGGER.info("  脏标记触发重建: {} 次", renderCache.getRebuildCount());
            LOGGER.info("  八叉树剔除: {} 个区块被跳过", totalCulledChunks);
            LOGGER.info("  动态 LOD: LOD0={} LOD1={} LOD2={}",
                dynamicLOD.getLODCount(0),
                dynamicLOD.getLODCount(1),
                dynamicLOD.getLODCount(2)
            );
            LOGGER.info("[L3 逻辑层]");
            LOGGER.info("  冻结区块: {} 个", totalFrozenChunks);
            LOGGER.info("  惰性实体: {} 个", totalLazyEntities);
            LOGGER.info("  红石快照: {} 条", totalRedstoneSnapshots);
            LOGGER.info("==========================================");
        });
    }

    private void displayHelp(MinecraftServer server) {
        server.execute(() -> {
            LOGGER.info("========== Stellar Core 帮助 ==========");
            LOGGER.info("/stellarcore stats  - 查看优化统计");
            LOGGER.info("/stellarcore reload - 重新加载配置（需 OP 权限）");
            LOGGER.info("/stellarcore help   - 显示此帮助");
            LOGGER.info("配置文件: config/stellar-core.properties");
            LOGGER.info("=======================================");
        });
    }

    private void reloadConfig() {
        this.config = StellarConfig.load();
        if (renderCache != null) renderCache.updateConfig(config);
        if (frustumCuller != null) frustumCuller.updateConfig(config);
        if (dynamicLOD != null) dynamicLOD.updateConfig(config);
        if (chunkStateManager != null) chunkStateManager.updateConfig(config);
        if (lazyEntityAI != null) lazyEntityAI.updateConfig(config);
        if (lazyRedstone != null) lazyRedstone.updateConfig(config);
        LOGGER.info("[StellarCore] 所有模块配置已热更新。");
    }

    public void incrementCachedFrames() { totalCachedFrames++; }
    public void addCulledChunks(long count) { totalCulledChunks += count; }
    public void addFrozenChunks(long count) { totalFrozenChunks += count; }
    public void addLazyEntities(long count) { totalLazyEntities += count; }
    public void addRedstoneSnapshots(long count) { totalRedstoneSnapshots += count; }

    public static StellarCore getInstance() { return instance; }
    public StellarConfig getConfig() { return config; }
    public ChunkRenderCache getRenderCache() { return renderCache; }
    public OctreeFrustumCuller getFrustumCuller() { return frustumCuller; }
    public DynamicLOD getDynamicLOD() { return dynamicLOD; }
    public ChunkStateManager getChunkStateManager() { return chunkStateManager; }
    public LazyEntityAI getLazyEntityAI() { return lazyEntityAI; }
    public LazyRedstone getLazyRedstone() { return lazyRedstone; }
}