package com.stellar.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.stellar.core.StellarCore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StellarConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String CONFIG_DIR = "config";
    public static final String CONFIG_FILE_NAME = "stellar-core.properties";
    public static final Path CONFIG_PATH = Paths.get(CONFIG_DIR, CONFIG_FILE_NAME);

    // ========== L1 渲染层配置 ==========

    /** 区块渲染缓存最大条目数 */
    public int renderCacheMaxEntries = 4096;

    /** 脏标记过期时间（毫秒），超过此时间强制重建 */
    public long renderCacheDirtyTimeoutMs = 5000;

    /** 八叉树最大深度 */
    public int octreeMaxDepth = 8;

    /** 八叉树节点最小尺寸（格） */
    public float octreeMinNodeSize = 16.0f;

    /** 视锥剔除：是否启用激进模式（可能产生极少量视觉瑕疵，但性能提升显著） */
    public boolean frustumAggressiveMode = true;

    /** 视锥剔除：扩展系数（>1.0 表示保留更多区块以防止边缘闪烁） */
    public float frustumExpansionFactor = 1.05f;

    /** 动态LOD：LOD1 切换距离（格） */
    public double lod1Distance = 64.0;

    /** 动态LOD：LOD2 切换距离（格） */
    public double lod2Distance = 128.0;

    /** 动态LOD：LOD3 切换距离（格） */
    public double lod3Distance = 256.0;

    /** 动态LOD：是否启用LOD3（极简模式，仅保留地面） */
    public boolean lod3Enabled = true;

    // ========== L3 逻辑层配置 ==========

    /** 区块状态机：ACTIVE 态半径（格），此范围内区块全模拟 */
    public double chunkActiveRadius = 64.0;

    /** 区块状态机：IDLE 态半径（格），此范围内仅红石/流体 */
    public double chunkIdleRadius = 128.0;

    /** 区块状态机：FROZEN 态半径（格），此范围内完全冻结 */
    public double chunkFrozenRadius = 256.0;

    /** 区块状态机：超出 FROZEN 半径的区块进入 POTENTIAL 态（仅存种子） */
    public boolean chunkPotentialEnabled = true;

    /** 实体AI惰性化：玩家视距外实体进入势能态的距离（格） */
    public double entityFreezeRadius = 64.0;

    /** 实体AI惰性化：快照过期时间（毫秒），超过此时间的快照强制刷新 */
    public long entitySnapshotTimeoutMs = 30000;

    /** 实体AI惰性化：是否对被动生物也启用惰性化 */
    public boolean entityLazyPassiveEnabled = true;

    /** 实体AI惰性化：是否对命名实体豁免惰性化 */
    public boolean entityNamedExempt = true;

    /** 红石惰性化：红石冻结半径（格） */
    public double redstoneFreezeRadius = 128.0;

    /** 红石惰性化：快照最大存储数量 */
    public int redstoneMaxSnapshots = 10000;

    /** 红石惰性化：是否对红石钟（比较器时钟）特殊处理 */
    public boolean redstoneClockOptimization = true;

    // ========== 粒子限制配置 ==========

    /** 全局最大粒子数 */
    public int maxParticles = 300;

    /** 是否对爆炸粒子单独放宽限制 */
    public boolean particleExplosionExempt = true;

    /** 爆炸粒子单独上限 */
    public int particleExplosionMax = 500;

    // ========== 调试配置 ==========

    /** 是否启用详细日志 */
    public boolean debugVerboseLogging = false;

    /** 是否启用性能基准测试模式 */
    public boolean debugBenchmarkMode = false;

    // ========== 构造器 ==========

    public StellarConfig() {
        // 使用默认值
    }

    // ========== 静态工厂方法 ==========

    /**
     * 加载配置。优先从 config/stellar-core.properties 读取 JSON 格式配置，
     * 若文件不存在或读取失败，使用默认值并自动创建配置文件。
     *
     * @return 配置实例
     */
    public static StellarConfig load() {
        StellarConfig config = new StellarConfig();

        File configFile = CONFIG_PATH.toFile();

        if (configFile.exists()) {
            LOGGER.info("[StellarCore] 从 {} 加载配置...", CONFIG_PATH);
            try (Reader reader = new FileReader(configFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                config = GSON.fromJson(json, StellarConfig.class);
                LOGGER.info("[StellarCore] 配置加载成功。");
            } catch (IOException e) {
                LOGGER.error("[StellarCore] 配置文件读取失败: {}", e.getMessage());
                LOGGER.info("[StellarCore] 将使用默认配置。");
                config = new StellarConfig();
            } catch (Exception e) {
                LOGGER.error("[StellarCore] 配置文件解析失败: {}", e.getMessage());
                LOGGER.info("[StellarCore] 将使用默认配置。");
                config = new StellarConfig();
            }
        } else {
            LOGGER.info("[StellarCore] 配置文件不存在，创建默认配置...");
        }

        // 无论是否读取成功，都保存一次配置文件
        // 如果文件不存在，创建默认文件
        // 如果文件存在但版本旧，更新文件以包含新增字段
        config.save();

        return config;
    }

    /**
     * 将当前配置保存到 config/stellar-core.properties。
     */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(this, writer);
            }
            LOGGER.info("[StellarCore] 配置已保存至: {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.error("[StellarCore] 配置文件写入失败: {}", e.getMessage());
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 创建一份默认配置的深拷贝。
     *
     * @return 默认配置的新实例
     */
    public static StellarConfig createDefault() {
        return new StellarConfig();
    }

    /**
     * 将另一个配置的值合并到当前配置（用于热更新）。
     * 仅当源配置的字段与当前配置不同时才更新。
     *
     * @param source 源配置
     */
    public void mergeFrom(StellarConfig source) {
        if (source == null) return;
        this.renderCacheMaxEntries = source.renderCacheMaxEntries;
        this.renderCacheDirtyTimeoutMs = source.renderCacheDirtyTimeoutMs;
        this.octreeMaxDepth = source.octreeMaxDepth;
        this.octreeMinNodeSize = source.octreeMinNodeSize;
        this.frustumAggressiveMode = source.frustumAggressiveMode;
        this.frustumExpansionFactor = source.frustumExpansionFactor;
        this.lod1Distance = source.lod1Distance;
        this.lod2Distance = source.lod2Distance;
        this.lod3Distance = source.lod3Distance;
        this.lod3Enabled = source.lod3Enabled;
        this.chunkActiveRadius = source.chunkActiveRadius;
        this.chunkIdleRadius = source.chunkIdleRadius;
        this.chunkFrozenRadius = source.chunkFrozenRadius;
        this.chunkPotentialEnabled = source.chunkPotentialEnabled;
        this.entityFreezeRadius = source.entityFreezeRadius;
        this.entitySnapshotTimeoutMs = source.entitySnapshotTimeoutMs;
        this.entityLazyPassiveEnabled = source.entityLazyPassiveEnabled;
        this.entityNamedExempt = source.entityNamedExempt;
        this.redstoneFreezeRadius = source.redstoneFreezeRadius;
        this.redstoneMaxSnapshots = source.redstoneMaxSnapshots;
        this.redstoneClockOptimization = source.redstoneClockOptimization;
        this.maxParticles = source.maxParticles;
        this.particleExplosionExempt = source.particleExplosionExempt;
        this.particleExplosionMax = source.particleExplosionMax;
        this.debugVerboseLogging = source.debugVerboseLogging;
        this.debugBenchmarkMode = source.debugBenchmarkMode;
    }

    /**
     * 获取 LOD 切换距离数组（便于模块直接遍历）。
     *
     * @return [lod1Distance, lod2Distance, lod3Distance]
     */
    public double[] getLODDistances() {
        return new double[] { lod1Distance, lod2Distance, lod3Distance };
    }

    /**
     * 获取区块状态半径数组（便于模块直接遍历）。
     *
     * @return [activeRadius, idleRadius, frozenRadius]
     */
    public double[] getChunkStateRadii() {
        return new double[] { chunkActiveRadius, chunkIdleRadius, chunkFrozenRadius };
    }
}