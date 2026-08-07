package com.stellar.core.render;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class OctreeFrustumCuller {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== 八叉树节点 ==========

    /**
     * 八叉树节点，代表三维空间中的一个立方体区域。
     * 每个节点要么是叶子节点（包含区块数据列表），
     * 要么是内部节点（包含 8 个子节点）。
     */
    public static class OctreeNode {
        /** 该节点所覆盖的空间范围 */
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;
        public final double centerX, centerY, centerZ;

        /** 当前深度（根节点深度为0） */
        public final int depth;

        /** 是否为叶子节点 */
        public boolean isLeaf;

        /** 该节点内的区块渲染数据（仅叶子节点使用） */
        public final List<ChunkRenderCache.RenderData> chunks;

        /** 该节点内的区块数量 */
        public int chunkCount;

        /** 子节点（仅内部节点使用） */
        public OctreeNode[] children;

        public OctreeNode(double minX, double minY, double minZ,
                          double maxX, double maxY, double maxZ, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.centerX = (minX + maxX) / 2.0;
            this.centerY = (minY + maxY) / 2.0;
            this.centerZ = (minZ + maxZ) / 2.0;
            this.depth = depth;
            this.isLeaf = true;
            this.chunks = new ArrayList<>();
            this.chunkCount = 0;
            this.children = null;
        }

        /**
         * 将该节点细分为 8 个子节点。
         * 细分后当前节点变为内部节点，chunks 列表清空。
         */
        public void subdivide() {
            if (!isLeaf) return;

            isLeaf = false;
            children = new OctreeNode[8];

            // 8 个子节点的半边长
            double halfX = (maxX - minX) / 2.0;
            double halfY = (maxY - minY) / 2.0;
            double halfZ = (maxZ - minZ) / 2.0;

            int childDepth = depth + 1;

            // 子节点编号（按 xyz 顺序）：
            // 0: (min, min, min)  1: (max, min, min)
            // 2: (min, max, min)  3: (max, max, min)
            // 4: (min, min, max)  5: (max, min, max)
            // 6: (min, max, max)  7: (max, max, max)
            children[0] = new OctreeNode(minX,        minY,        minZ,
                                         centerX,     centerY,     centerZ,     childDepth);
            children[1] = new OctreeNode(centerX,     minY,        minZ,
                                         maxX,        centerY,     centerZ,     childDepth);
            children[2] = new OctreeNode(minX,        centerY,     minZ,
                                         centerX,     maxY,        centerZ,     childDepth);
            children[3] = new OctreeNode(centerX,     centerY,     minZ,
                                         maxX,        maxY,        centerZ,     childDepth);
            children[4] = new OctreeNode(minX,        minY,        centerZ,
                                         centerX,     centerY,     maxZ,        childDepth);
            children[5] = new OctreeNode(centerX,     minY,        centerZ,
                                         maxX,        centerY,     maxZ,        childDepth);
            children[6] = new OctreeNode(minX,        centerY,     centerZ,
                                         centerX,     maxY,        maxZ,        childDepth);
            children[7] = new OctreeNode(centerX,     centerY,     centerZ,
                                         maxX,        maxY,        maxZ,        childDepth);

            // 将现有区块重新分配到子节点
            for (ChunkRenderCache.RenderData chunk : chunks) {
                int index = getChildIndexForChunk(chunk);
                if (index >= 0 && index < 8) {
                    children[index].chunks.add(chunk);
                    children[index].chunkCount++;
                }
            }

            // 清空当前节点的区块列表
            chunks.clear();
            chunkCount = 0;
        }

        /**
         * 根据区块的中心坐标确定应分配到哪个子节点。
         */
        private int getChildIndexForChunk(ChunkRenderCache.RenderData chunk) {
            int index = 0;
            if (chunk.centerX >= centerX) index |= 1;
            if (chunk.centerY >= centerY) index |= 2;
            if (chunk.centerZ >= centerZ) index |= 4;
            return index;
        }
    }

    // ========== 八叉树实现 ==========

    /** 八叉树根节点 */
    private OctreeNode root;

    /** 最大深度 */
    private int maxDepth;

    /** 最小节点尺寸（格），到达此尺寸后不再细分 */
    private float minNodeSize;

    /** 读/写锁，支持并发读取 */
    private final ReadWriteLock lock;

    /** 当前树中区块总数 */
    private int totalChunks;

    // ========== 统计计数器 ==========

    private long totalQueries = 0;
    private long totalCulled = 0;

    // ========== 构造器 ==========

    public OctreeFrustumCuller(StellarConfig config) {
        this.maxDepth = config.octreeMaxDepth;
        this.minNodeSize = config.octreeMinNodeSize;
        this.lock = new ReentrantReadWriteLock();

        // 构建覆盖整个 Minecraft 世界的根节点
        // 实际只覆盖玩家周围区域，这里设为 ±1024 格（64 个区块）范围
        double worldRange = 1024.0;
        double worldMinY = -64.0;
        double worldMaxY = 320.0;

        this.root = new OctreeNode(-worldRange, worldMinY, -worldRange,
                                    worldRange, worldMaxY, worldRange, 0);
        this.totalChunks = 0;

        if (config.debugVerboseLogging) {
            LOGGER.info("[OctreeFrustumCuller] 八叉树初始化完成。maxDepth={}, minNodeSize={}, 根节点范围={}",
                maxDepth, minNodeSize, worldRange);
        }
    }

    // ========== 公共 API：插入 ==========

    /**
     * 将一个区块的渲染数据插入八叉树。
     *
     * @param chunk 区块渲染数据
     */
    public void insert(ChunkRenderCache.RenderData chunk) {
        if (chunk == null) return;

        lock.writeLock().lock();
        try {
            insertRecursive(root, chunk);
            totalChunks++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量插入区块渲染数据。
     *
     * @param chunks 区块渲染数据列表
     */
    public void insertBatch(List<ChunkRenderCache.RenderData> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        lock.writeLock().lock();
        try {
            for (ChunkRenderCache.RenderData chunk : chunks) {
                if (chunk != null) {
                    insertRecursive(root, chunk);
                    totalChunks++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 递归插入区块到八叉树。
     * 如果节点已到达最大深度或最小尺寸，直接加入叶子节点。
     * 否则，检查是否需要细分并递归到对应子节点。
     */
    private void insertRecursive(OctreeNode node, ChunkRenderCache.RenderData chunk) {
        // 检查是否到达叶子节点的条件
        double nodeSize = node.maxX - node.minX;
        if (node.depth >= maxDepth || nodeSize <= minNodeSize * 2.0) {
            node.chunks.add(chunk);
            node.chunkCount++;
            return;
        }

        // 如果是叶子节点但未到达限制，先细分
        if (node.isLeaf) {
            // 如果当前叶子节点区块较少，暂不细分
            if (node.chunkCount < 16) {
                node.chunks.add(chunk);
                node.chunkCount++;
                return;
            }
            node.subdivide();
        }

        // 递归到对应子节点
        int childIndex = getChildIndex(node, chunk);
        if (childIndex >= 0 && childIndex < 8 && node.children != null) {
            insertRecursive(node.children[childIndex], chunk);
        }
    }

    /**
     * 确定区块应放入哪个子节点。
     */
    private int getChildIndex(OctreeNode node, ChunkRenderCache.RenderData chunk) {
        int index = 0;
        if (chunk.centerX >= node.centerX) index |= 1;
        if (chunk.centerY >= node.centerY) index |= 2;
        if (chunk.centerZ >= node.centerZ) index |= 4;
        return index;
    }

    // ========== 公共 API：移除 ==========

    /**
     * 从八叉树中移除一个区块。
     *
     * @param chunk 区块渲染数据
     * @return 是否成功移除
     */
    public boolean remove(ChunkRenderCache.RenderData chunk) {
        if (chunk == null) return false;

        lock.writeLock().lock();
        try {
            boolean removed = removeRecursive(root, chunk);
            if (removed) {
                totalChunks--;
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 递归移除区块。
     */
    private boolean removeRecursive(OctreeNode node, ChunkRenderCache.RenderData chunk) {
        if (node.isLeaf) {
            boolean removed = node.chunks.remove(chunk);
            if (removed) {
                node.chunkCount--;
            }
            return removed;
        }

        int childIndex = getChildIndex(node, chunk);
        if (childIndex >= 0 && childIndex < 8 && node.children != null) {
            OctreeNode child = node.children[childIndex];
            if (removeRecursive(child, chunk)) {
                // 检查是否需要合并子节点
                if (shouldMerge(node)) {
                    mergeChildren(node);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 检查内部节点是否应该合并子节点（所有子节点总区块数 < 阈值）。
     */
    private boolean shouldMerge(OctreeNode node) {
        if (node.isLeaf || node.children == null) return false;
        int totalChildChunks = 0;
        for (OctreeNode child : node.children) {
            totalChildChunks += child.chunkCount;
        }
        return totalChildChunks < 8;
    }

    /**
     * 将内部节点的所有子节点合并回当前节点。
     */
    private void mergeChildren(OctreeNode node) {
        if (node.isLeaf || node.children == null) return;

        for (OctreeNode child : node.children) {
            // 递归合并子节点的子节点
            if (!child.isLeaf) {
                mergeChildren(child);
            }
            // 将子节点的所有区块移到当前节点
            node.chunks.addAll(child.chunks);
            node.chunkCount += child.chunkCount;
        }
        node.children = null;
        node.isLeaf = true;
    }

    // ========== 公共 API：查询 ==========

    /**
     * 查询视锥体内可见的区块。
     * 使用层次包围盒快速剔除不可见的节点。
     *
     * @param cameraPos  相机位置
     * @param lookVec    视线方向
     * @param fovX       水平视场角（度）
     * @param fovY       垂直视场角（度）
     * @param renderDist 渲染距离
     * @return 可见区块的渲染数据列表
     */
    public List<ChunkRenderCache.RenderData> queryVisible(
            Vec3d cameraPos, Vec3d lookVec,
            double fovX, double fovY, double renderDist) {

        totalQueries++;
        List<ChunkRenderCache.RenderData> result = new ArrayList<>();

        lock.readLock().lock();
        try {
            // 构建视锥体包围盒（简化：使用相机前方 renderDist 范围内的球体）
            double minX = cameraPos.x - renderDist;
            double maxX = cameraPos.x + renderDist;
            double minY = cameraPos.y - renderDist;
            double maxY = cameraPos.y + renderDist;
            double minZ = cameraPos.z - renderDist;
            double maxZ = cameraPos.z + renderDist;

            Box frustumBox = new Box(minX, minY, minZ, maxX, maxY, maxZ);

            // 递归查询
            queryRecursive(root, cameraPos, frustumBox, renderDist, result);

            totalCulled += (totalChunks - result.size());
        } finally {
            lock.readLock().unlock();
        }

        return result;
    }

    /**
     * 递归查询可见区块。
     * 先检查节点包围盒是否与视锥体球相交，
     * 如果不相交则跳过整个节点（这是八叉树剔除的核心优势）。
     */
    private void queryRecursive(OctreeNode node, Vec3d cameraPos,
                                 Box frustumBox, double renderDist,
                                 List<ChunkRenderCache.RenderData> result) {

        // 检查节点包围盒是否与视锥体范围相交
        Box nodeBox = new Box(node.minX, node.minY, node.minZ,
                              node.maxX, node.maxY, node.maxZ);
        if (!nodeBox.intersects(frustumBox)) {
            return; // 整个节点不可见，跳过
        }

        if (node.isLeaf) {
            // 叶子节点：逐个检查区块
            for (ChunkRenderCache.RenderData chunk : node.chunks) {
                double dx = chunk.centerX - cameraPos.x;
                double dy = chunk.centerY - cameraPos.y;
                double dz = chunk.centerZ - cameraPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= renderDist * renderDist) {
                    chunk.isCulled = false;
                    result.add(chunk);
                } else {
                    chunk.isCulled = true;
                }
            }
        } else if (node.children != null) {
            // 内部节点：递归检查子节点
            for (OctreeNode child : node.children) {
                queryRecursive(child, cameraPos, frustumBox, renderDist, result);
            }
        }
    }

    // ========== 公共 API：清空与重建 ==========

    /**
     * 清空八叉树，保留根节点结构。
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            double worldRange = root.maxX; // 保留原始范围
            root = new OctreeNode(-worldRange, root.minY, -worldRange,
                                   worldRange, root.maxY, worldRange, 0);
            totalChunks = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ========== 配置更新 ==========

    /**
     * 热更新配置。
     */
    public void updateConfig(StellarConfig config) {
        this.maxDepth = config.octreeMaxDepth;
        this.minNodeSize = config.octreeMinNodeSize;

        if (config.debugVerboseLogging) {
            LOGGER.info("[OctreeFrustumCuller] 配置已更新。maxDepth={}, minNodeSize={}, 当前区块数={}",
                maxDepth, minNodeSize, totalChunks);
        }
    }

    // ========== 统计查询 ==========

    public int getTotalChunks() { return totalChunks; }
    public long getTotalQueries() { return totalQueries; }
    public long getTotalCulled() { return totalCulled; }

    /**
     * 获取剔除率（百分比）。
     *
     * @return 剔除率（0-100）
     */
    public double getCullRate() {
        if (totalQueries == 0) return 0.0;
        return (double) totalCulled / (totalChunks * totalQueries) * 100.0;
    }
}