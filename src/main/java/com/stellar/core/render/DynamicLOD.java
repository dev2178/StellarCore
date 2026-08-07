package com.stellar.core.render;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DynamicLOD {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== LOD 级别常量 ==========

    /** LOD 0：完整细节 —— 渲染所有方块、方块实体、实体 */
    public static final int LOD_FULL = 0;

    /** LOD 1：半细节 —— 跳过小型方块实体渲染，降低纹理分辨率 */
    public static final int LOD_HALF = 1;

    /** LOD 2：极简 —— 仅渲染完整方块表面，跳过所有方块实体和实体 */
    public static final int LOD_MINIMAL = 2;

    /** LOD 3：仅地面 —— 仅渲染地形高度图，用于极远距离 */
    public static final int LOD_GROUND = 3;

    // ========== LOD 描述映射 ==========

    /** 每个区块当前的 LOD 级别 */
    private final Map<ChunkPos, Integer> lodMap;

    /** LOD 切换距离 */
    private double lod1Distance;
    private double lod2Distance;
    private double lod3Distance;

    /** 是否启用 LOD3 */
    private boolean lod3Enabled;

    // ========== LOD 级别计数器 ==========

    private final long[] lodCounters = new long[4]; // [LOD0, LOD1, LOD2, LOD3]

    // ========== 构造器 ==========

    public DynamicLOD(StellarConfig config) {
        this.lodMap = new ConcurrentHashMap<>();
        this.lod1Distance = config.lod1Distance;
        this.lod2Distance = config.lod2Distance;
        this.lod3Distance = config.lod3Distance;
        this.lod3Enabled = config.lod3Enabled;

        for (int i = 0; i < 4; i++) {
            lodCounters[i] = 0;
        }

        if (config.debugVerboseLogging) {
            LOGGER.info("[DynamicLOD] 初始化完成。LOD1={}m, LOD2={}m, LOD3={}m, LOD3启用={}",
                lod1Distance, lod2Distance, lod3Distance, lod3Enabled);
        }
    }

    // ========== 公共 API：LOD 级别判定 ==========

    /**
     * 根据区块与相机的距离确定 LOD 级别。
     *
     * @param chunkPos  区块坐标
     * @param cameraPos 相机位置（玩家眼睛位置）
     * @return LOD 级别（0-3）
     */
    public int getLODLevel(ChunkPos chunkPos, Vec3d cameraPos) {
        if (chunkPos == null || cameraPos == null) {
            return LOD_FULL;
        }

        // 计算区块中心到相机的距离
        double centerX = chunkPos.getStartX() + 8.0;
        double centerZ = chunkPos.getStartZ() + 8.0;
        double centerY = 128.0; // 区块垂直中心

        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        int level;
        if (distance < lod1Distance) {
            level = LOD_FULL;
        } else if (distance < lod2Distance) {
            level = LOD_HALF;
        } else if (distance < lod3Distance || !lod3Enabled) {
            level = LOD_MINIMAL;
        } else {
            level = LOD_GROUND;
        }

        // 更新该区块的 LOD 记录
        Integer previousLevel = lodMap.put(chunkPos, level);
        if (previousLevel == null || previousLevel != level) {
            // LOD 级别发生变化，更新计数器
            if (previousLevel != null) {
                lodCounters[previousLevel]--;
            }
            lodCounters[level]++;
        }

        return level;
    }

    /**
     * 使用区块中心直接坐标计算 LOD 级别（避免创建 ChunkPos 对象）。
     *
     * @param chunkCenterX 区块中心 X 坐标
     * @param chunkCenterZ 区块中心 Z 坐标
     * @param cameraPos    相机位置
     * @return LOD 级别（0-3）
     */
    public int getLODLevelFast(double chunkCenterX, double chunkCenterZ, Vec3d cameraPos) {
        if (cameraPos == null) return LOD_FULL;

        double dx = chunkCenterX - cameraPos.x;
        double dy = 128.0 - cameraPos.y;
        double dz = chunkCenterZ - cameraPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance < lod1Distance) return LOD_FULL;
        if (distance < lod2Distance) return LOD_HALF;
        if (distance < lod3Distance || !lod3Enabled) return LOD_MINIMAL;
        return LOD_GROUND;
    }

    /**
     * 获取区块当前的 LOD 级别（从缓存中读取，不重新计算距离）。
     *
     * @param chunkPos 区块坐标
     * @return LOD 级别，如果未记录则返回 LOD_FULL
     */
    public int getCurrentLOD(ChunkPos chunkPos) {
        Integer level = lodMap.get(chunkPos);
        return level != null ? level : LOD_FULL;
    }

    // ========== 公共 API：LOD 信息查询 ==========

    /**
     * 获取某个 LOD 级别的渲染缩放因子。
     * 该因子用于调整区块渲染的顶点密度。
     *
     * @param lodLevel LOD 级别
     * @return 缩放因子（1.0 = 完整, 0.5 = 一半密度, 0.25 = 四分之一密度）
     */
    public static double getLODScale(int lodLevel) {
        switch (lodLevel) {
            case LOD_FULL:    return 1.0;
            case LOD_HALF:    return 0.5;
            case LOD_MINIMAL: return 0.25;
            case LOD_GROUND:  return 0.125;
            default:          return 1.0;
        }
    }

    /**
     * 获取 LOD 级别的人类可读名称。
     *
     * @param lodLevel LOD 级别
     * @return 名称字符串
     */
    public static String getLODName(int lodLevel) {
        switch (lodLevel) {
            case LOD_FULL:    return "完整细节";
            case LOD_HALF:    return "半细节";
            case LOD_MINIMAL: return "极简";
            case LOD_GROUND:  return "仅地面";
            default:          return "未知";
        }
    }

    /**
     * 判断某个 LOD 级别是否应渲染方块实体。
     *
     * @param lodLevel LOD 级别
     * @return true 表示需要渲染方块实体
     */
    public static boolean shouldRenderBlockEntities(int lodLevel) {
        return lodLevel <= LOD_FULL;
    }

    /**
     * 判断某个 LOD 级别是否应渲染实体（生物、物品等）。
     *
     * @param lodLevel LOD 级别
     * @return true 表示需要渲染实体
     */
    public static boolean shouldRenderEntities(int lodLevel) {
        return lodLevel <= LOD_HALF;
    }

    /**
     * 判断某个 LOD 级别是否应渲染粒子效果。
     *
     * @param lodLevel LOD 级别
     * @return true 表示需要渲染粒子
     */
    public static boolean shouldRenderParticles(int lodLevel) {
        return lodLevel <= LOD_HALF;
    }

    // ========== 公共 API：区块清理 ==========

    /**
     * 当区块被卸载时调用，从 LOD 映射中移除该区块。
     *
     * @param chunkPos 区块坐标
     */
    public void onChunkUnload(ChunkPos chunkPos) {
        Integer previousLevel = lodMap.remove(chunkPos);
        if (previousLevel != null) {
            lodCounters[previousLevel]--;
        }
    }

    /**
     * 批量清理多个区块的 LOD 记录。
     *
     * @param positions 区块坐标数组
     */
    public void onChunksUnload(ChunkPos[] positions) {
        if (positions == null) return;
        for (ChunkPos pos : positions) {
            if (pos != null) {
                Integer previousLevel = lodMap.remove(pos);
                if (previousLevel != null) {
                    lodCounters[previousLevel]--;
                }
            }
        }
    }

    // ========== 公共 API：LOD 过渡 ==========

    /**
     * 计算两个 LOD 级别之间的过渡因子。
     * 用于在区块 LOD 切换时实现平滑过渡（淡化而不是突然跳变）。
     *
     * @param chunkPos  区块坐标
     * @param cameraPos 相机位置
     * @return 过渡因子（0.0 = 完全使用低级别, 1.0 = 完全使用高级别）
     */
    public double getTransitionFactor(ChunkPos chunkPos, Vec3d cameraPos) {
        if (chunkPos == null || cameraPos == null) return 1.0;

        double centerX = chunkPos.getStartX() + 8.0;
        double centerZ = chunkPos.getStartZ() + 8.0;
        double dx = centerX - cameraPos.x;
        double dy = 128.0 - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 过渡区间宽度（格）
        double transitionZone = 8.0;

        // 检查是否在 LOD1 过渡区
        if (distance >= lod1Distance - transitionZone && distance <= lod1Distance + transitionZone) {
            return 1.0 - (distance - (lod1Distance - transitionZone)) / (2.0 * transitionZone);
        }
        // 检查是否在 LOD2 过渡区
        if (distance >= lod2Distance - transitionZone && distance <= lod2Distance + transitionZone) {
            return 1.0 - (distance - (lod2Distance - transitionZone)) / (2.0 * transitionZone);
        }
        // 检查是否在 LOD3 过渡区
        if (lod3Enabled && distance >= lod3Distance - transitionZone
            && distance <= lod3Distance + transitionZone) {
            return 1.0 - (distance - (lod3Distance - transitionZone)) / (2.0 * transitionZone);
        }

        return 1.0;
    }

    // ========== 配置更新 ==========

    /**
     * 热更新配置。
     */
    public void updateConfig(StellarConfig config) {
        this.lod1Distance = config.lod1Distance;
        this.lod2Distance = config.lod2Distance;
        this.lod3Distance = config.lod3Distance;
        this.lod3Enabled = config.lod3Enabled;

        if (config.debugVerboseLogging) {
            LOGGER.info("[DynamicLOD] 配置已更新。LOD1={}m, LOD2={}m, LOD3={}m, LOD3启用={}",
                lod1Distance, lod2Distance, lod3Distance, lod3Enabled);
        }

        // 配置变更后，现有 LOD 级别可能不再准确
        // 不清除映射，下一次 getLODLevel() 调用时会自动更新
    }

    // ========== 统计查询 ==========

    /**
     * 获取某个 LOD 级别的当前区块数量。
     *
     * @param lodLevel LOD 级别（0-3）
     * @return 区块数量
     */
    public long getLODCount(int lodLevel) {
        if (lodLevel < 0 || lodLevel > 3) return 0;
        return lodCounters[lodLevel];
    }

    /**
     * 获取 LOD 映射的总条目数。
     */
    public int getTotalEntries() {
        return lodMap.size();
    }

    /**
     * 获取所有 LOD 级别的区块分布（调试用）。
     *
     * @return 格式化字符串
     */
    public String getDistributionString() {
        return String.format("LOD0=%d LOD1=%d LOD2=%d LOD3=%d (总计:%d)",
            lodCounters[0], lodCounters[1], lodCounters[2], lodCounters[3],
            lodMap.size());
    }
}