package com.stellar.core.render;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkRenderCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== 数据结构 ==========

    /**
     * 渲染数据容器，存储一个区块的预构建渲染信息。
     */
    public static class RenderData {
        /** 该渲染数据对应的区块坐标 */
        public final ChunkPos pos;
        /** 渲染数据构建时间戳（毫秒） */
        public long buildTimestamp;
        /** 区块内非空气方块数量（用于快速判断是否为空区块） */
        public int solidBlockCount;
        /** 区块内方块实体的数量（箱子、熔炉等） */
        public int blockEntityCount;
        /** 该区块是否包含半透明方块（影响渲染顺序） */
        public boolean hasTranslucentBlocks;
        /** 该区块的 LOD 级别（0=完整, 1=半细节, 2=极简, 3=仅地面） */
        public int lodLevel;
        /** 该区块是否已被视锥剔除（缓存此结果以避免重复计算） */
        public boolean isCulled;
        /** 该区块中心点的世界坐标（用于距离计算） */
        public final double centerX;
        public final double centerY;
        public final double centerZ;
        /** 该区块的包围盒最小/最大坐标 */
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;

        public RenderData(ChunkPos pos) {
            this.pos = pos;
            this.buildTimestamp = System.currentTimeMillis();
            this.solidBlockCount = 0;
            this.blockEntityCount = 0;
            this.hasTranslucentBlocks = false;
            this.lodLevel = 0;
            this.isCulled = false;

            int chunkStartX = pos.getStartX();
            int chunkStartZ = pos.getStartZ();
            this.centerX = chunkStartX + 8.0;
            this.centerY = 128.0;
            this.centerZ = chunkStartZ + 8.0;
            this.minX = chunkStartX;
            this.minY = -64.0;
            this.minZ = chunkStartZ;
            this.maxX = chunkStartX + 16.0;
            this.maxY = 320.0;
            this.maxZ = chunkStartZ + 16.0;
        }

        /**
         * 更新渲染数据的时间戳，标记为最新。
         */
        public void touch() {
            this.buildTimestamp = System.currentTimeMillis();
        }

        /**
         * 检查渲染数据是否过期。
         *
         * @param timeoutMs 过期时间（毫秒）
         * @return true 表示数据已过期需要重建
         */
        public boolean isExpired(long timeoutMs) {
            return System.currentTimeMillis() - buildTimestamp > timeoutMs;
        }
    }

    // ========== 缓存存储 ==========

    /** 主缓存：ChunkPos → RenderData */
    private final Map<ChunkPos, RenderData> cache;

    /** 脏标记集合：需要重建的区块 */
    private final Set<ChunkPos> dirtyChunks;

    /** 最大缓存条目数（超过后淘汰最旧条目） */
    private int maxEntries;

    /** 脏标记过期时间（毫秒） */
    private long dirtyTimeoutMs;

    // ========== 统计计数器 ==========

    private long cacheHits = 0;
    private long totalRequests = 0;
    private long rebuildCount = 0;
    private long evictionCount = 0;
    private long totalInsertions = 0;

    // ========== 构造器 ==========

    public ChunkRenderCache(StellarConfig config) {
        this.cache = new ConcurrentHashMap<>();
        this.dirtyChunks = ConcurrentHashMap.newKeySet();
        this.maxEntries = config.renderCacheMaxEntries;
        this.dirtyTimeoutMs = config.renderCacheDirtyTimeoutMs;

        if (config.debugVerboseLogging) {
            LOGGER.info("[ChunkRenderCache] 初始化完成。maxEntries={}, dirtyTimeoutMs={}",
                maxEntries, dirtyTimeoutMs);
        }
    }

    // ========== 公共 API ==========

    /**
     * 标记一个区块为"脏"，下次调用 getOrBuild 时将强制重建。
     * 当区块内方块发生变化时（玩家放置/破坏方块、方块更新等）调用此方法。
     *
     * @param pos 需要标记的区块坐标
     */
    public void markDirty(ChunkPos pos) {
        if (pos == null) return;
        dirtyChunks.add(pos);
        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[ChunkRenderCache] 标记脏区块: {}", pos);
        }
    }

    /**
     * 批量标记多个区块为脏。
     *
     * @param positions 区块坐标数组
     */
    public void markDirtyBatch(ChunkPos[] positions) {
        if (positions == null) return;
        for (ChunkPos pos : positions) {
            if (pos != null) {
                dirtyChunks.add(pos);
            }
        }
    }

    /**
     * 获取或构建区块的渲染数据。
     * 如果缓存命中且未标记为脏且未过期，直接返回缓存数据。
     * 否则，使用传入的 WorldChunk 重新构建渲染数据。
     *
     * @param pos   区块坐标
     * @param chunk 世界区块对象（用于提取渲染信息）
     * @return 该区块的渲染数据
     */
    public RenderData getOrBuild(ChunkPos pos, WorldChunk chunk) {
        totalRequests++;

        if (pos == null) {
            cacheHits++; // null 请求视为命中，避免空指针
            return null;
        }

        RenderData data = cache.get(pos);

        // 缓存命中：数据存在、未标记为脏、未过期
        if (data != null
            && !dirtyChunks.contains(pos)
            && !data.isExpired(dirtyTimeoutMs)) {
            cacheHits++;
            return data;
        }

        // 缓存未命中或需要重建
        rebuildCount++;
        data = buildRenderData(pos, chunk);

        // 检查缓存容量，必要时淘汰
        if (cache.size() >= maxEntries && !cache.containsKey(pos)) {
            evictOldest();
        }

        // 放入缓存并清除脏标记
        cache.put(pos, data);
        dirtyChunks.remove(pos);
        totalInsertions++;

        return data;
    }

    /**
     * 直接从缓存中获取渲染数据（不触发重建）。
     *
     * @param pos 区块坐标
     * @return 渲染数据，如果缓存中不存在则返回 null
     */
    public RenderData getIfPresent(ChunkPos pos) {
        totalRequests++;
        if (pos == null) {
            cacheHits++;
            return null;
        }
        RenderData data = cache.get(pos);
        if (data != null) {
            cacheHits++;
        }
        return data;
    }

    /**
     * 使指定区块的缓存失效。
     *
     * @param pos 区块坐标
     */
    public void invalidate(ChunkPos pos) {
        if (pos == null) return;
        cache.remove(pos);
        dirtyChunks.remove(pos);
    }

    /**
     * 清空所有缓存。
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        dirtyChunks.clear();
        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[ChunkRenderCache] 缓存已清空。清除了 {} 个条目。", size);
        }
    }

    // ========== 配置更新 ==========

    /**
     * 热更新配置。
     *
     * @param config 新配置
     */
    public void updateConfig(StellarConfig config) {
        this.maxEntries = config.renderCacheMaxEntries;
        this.dirtyTimeoutMs = config.renderCacheDirtyTimeoutMs;

        // 如果新配置的缓存容量变小，淘汰多余条目
        while (cache.size() > maxEntries) {
            evictOldest();
        }

        if (config.debugVerboseLogging) {
            LOGGER.info("[ChunkRenderCache] 配置已更新。maxEntries={}, dirtyTimeoutMs={}, 当前缓存条目={}",
                maxEntries, dirtyTimeoutMs, cache.size());
        }
    }

    // ========== 统计查询 ==========

    public long getCacheHits() { return cacheHits; }
    public long getTotalRequests() { return totalRequests; }
    public long getRebuildCount() { return rebuildCount; }
    public long getEvictionCount() { return evictionCount; }
    public int getCacheSize() { return cache.size(); }
    public int getDirtyCount() { return dirtyChunks.size(); }

    /**
     * 获取缓存命中率（百分比）。
     *
     * @return 命中率（0-100）
     */
    public double getHitRate() {
        if (totalRequests == 0) return 0.0;
        return (double) cacheHits / totalRequests * 100.0;
    }

    // ========== 内部方法 ==========

    /**
     * 从 WorldChunk 提取渲染相关数据。
     * 该方法遍历区块的每个子区块（ChunkSection），统计实体方块数量、
     * 方块实体数量、是否存在半透明方块等信息。
     *
     * @param pos   区块坐标
     * @param chunk 世界区块对象
     * @return 新构建的渲染数据
     */
    private RenderData buildRenderData(ChunkPos pos, WorldChunk chunk) {
        RenderData data = new RenderData(pos);

        if (chunk == null) {
            // chunk 为 null 时返回空的 RenderData，后续 LOD 系统会处理
            data.solidBlockCount = 0;
            data.blockEntityCount = 0;
            data.hasTranslucentBlocks = false;
            return data;
        }

        int solidCount = 0;
        int entityCount = 0;
        boolean translucent = false;

        // 遍历该区块在垂直方向上的所有子区块（Section）
        // Minecraft 1.20.1 世界高度为 -64 到 320，共 24 个 section
        int bottomSection = chunk.getBottomSectionCoord();
        int topSection = chunk.getTopSectionCoord();

        for (int sectionY = bottomSection; sectionY <= topSection; sectionY++) {
            int sectionIndex = chunk.getSectionIndex(sectionY);
            net.minecraft.world.chunk.ChunkSection section = chunk.getSection(sectionIndex);

            if (section == null || section.isEmpty()) {
                continue;
            }

            // 遍历子区块内的所有方块（16×16×16 = 4096）
            net.minecraft.block.BlockState blockState;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        blockState = section.getBlockState(x, y, z);
                        if (!blockState.isAir()) {
                            solidCount++;
                        }
                        // 检查是否为半透明方块（玻璃、冰、水等）
                        if (!blockState.isOpaque()
                            && !blockState.isAir()
                            && !translucent) {
                            // 只检查一次，一旦发现就标记
                            // 使用 isOpaque() 的反向来快速判断
                        }
                    }
                }
            }
        }

        // 统计方块实体
        if (chunk.getBlockEntities() != null) {
            for (net.minecraft.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
                if (be != null) {
                    entityCount++;
                }
            }
        }

        // 检查是否存在半透明方块（遍历完成后统一检查）
        for (int sectionY = bottomSection; sectionY <= topSection && !translucent; sectionY++) {
            int sectionIndex = chunk.getSectionIndex(sectionY);
            net.minecraft.world.chunk.ChunkSection section = chunk.getSection(sectionIndex);
            if (section == null || section.isEmpty()) continue;
            // 使用 hasNonOpaqueBlocks 快速检查（Mojang Mapping）
            // 此方法在 ChunkSection 中预计算，比逐格检查快得多
            if (section.hasNonOpaqueBlocks()) {
                translucent = true;
                break;
            }
        }

        data.solidBlockCount = solidCount;
        data.blockEntityCount = entityCount;
        data.hasTranslucentBlocks = translucent;
        data.touch();

        return data;
    }

    /**
     * 淘汰最旧的缓存条目。
     * 遍历缓存找到 buildTimestamp 最小的条目并移除。
     * 同时清理其脏标记（如果存在）。
     */
    private void evictOldest() {
        if (cache.isEmpty()) return;

        ChunkPos oldestPos = null;
        long oldestTimestamp = Long.MAX_VALUE;

        for (Map.Entry<ChunkPos, RenderData> entry : cache.entrySet()) {
            if (entry.getValue().buildTimestamp < oldestTimestamp) {
                oldestTimestamp = entry.getValue().buildTimestamp;
                oldestPos = entry.getKey();
            }
        }

        if (oldestPos != null) {
            cache.remove(oldestPos);
            dirtyChunks.remove(oldestPos);
            evictionCount++;
        }
    }
}