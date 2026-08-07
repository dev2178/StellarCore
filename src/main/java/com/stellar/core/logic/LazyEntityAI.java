package com.stellar.core.logic;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LazyEntityAI {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== 实体快照数据结构 ==========

    /**
     * 实体快照，存储实体进入势能态时的关键状态。
     * 当玩家重新接近时，从此快照恢复并"加速模拟"。
     */
    public static class EntitySnapshot {
        /** 实体 UUID */
        public final UUID entityUuid;
        /** 实体类型（用于验证快照是否仍然有效） */
        public final String entityType;
        /** 快照创建时的位置 */
        public final double posX;
        public final double posY;
        public final double posZ;
        /** 快照创建时的速度向量 */
        public final double velocityX;
        public final double velocityY;
        public final double velocityZ;
        /** 快照创建时的时间戳（系统毫秒） */
        public final long timestamp;
        /** 快照创建时实体所在的世界时间（游戏刻） */
        public final long worldTime;
        /** 实体当前生命值（用于恢复） */
        public final float health;
        /** 实体是否存活 */
        public final boolean isAlive;
        /** 实体是否在火焰中 */
        public final boolean isOnFire;
        /** 实体剩余的火焰刻数 */
        public final int fireTicks;
        /** 实体的自定义名称（如果有的话） */
        public final String customName;
        /** 实体是否被命名（命名实体可豁免惰性化） */
        public final boolean hasCustomName;
        /** 实体是否为幼年 */
        public final boolean isBaby;
        /** 实体的 Yaw 角度 */
        public final float yaw;
        /** 实体的 Pitch 角度 */
        public final float pitch;

        public EntitySnapshot(Entity entity) {
            this.entityUuid = entity.getUuid();
            this.entityType = entity.getType().getUntranslatedName();
            this.posX = entity.getX();
            this.posY = entity.getY();
            this.posZ = entity.getZ();
            this.velocityX = entity.getVelocity().x;
            this.velocityY = entity.getVelocity().y;
            this.velocityZ = entity.getVelocity().z;
            this.timestamp = System.currentTimeMillis();
            this.worldTime = entity.getWorld().getTime();
            this.isAlive = entity.isAlive();
            this.isOnFire = entity.isOnFire();
            this.fireTicks = entity.getFireTicks();
            this.hasCustomName = entity.hasCustomName();
            this.customName = entity.hasCustomName() ? entity.getCustomName().getString() : null;
            this.yaw = entity.getYaw();
            this.pitch = entity.getPitch();

            if (entity instanceof LivingEntity living) {
                this.health = living.getHealth();
                this.isBaby = living.isBaby();
            } else {
                this.health = 0.0f;
                this.isBaby = false;
            }
        }

        /**
         * 计算从快照创建到现在经过的游戏刻数。
         *
         * @param currentWorldTime 当前世界时间
         * @return 经过的游戏刻数
         */
        public long getElapsedTicks(long currentWorldTime) {
            return Math.max(0, currentWorldTime - worldTime);
        }

        /**
         * 计算从快照创建到现在经过的真实时间（毫秒）。
         *
         * @return 经过的毫秒数
         */
        public long getElapsedMillis() {
            return System.currentTimeMillis() - timestamp;
        }

        /**
         * 基于速度和经过时间预测实体当前位置。
         * 假设实体在惰性化期间以恒定速度移动。
         *
         * @param currentWorldTime 当前世界时间
         * @return 预测位置
         */
        public Vec3d predictPosition(long currentWorldTime) {
            long elapsedTicks = getElapsedTicks(currentWorldTime);
            // 每 tick 实体移动 velocity 距离
            double predictedX = posX + velocityX * elapsedTicks;
            double predictedY = posY + velocityY * elapsedTicks;
            double predictedZ = posZ + velocityZ * elapsedTicks;
            return new Vec3d(predictedX, predictedY, predictedZ);
        }

        /**
         * 检查快照是否已过期。
         *
         * @param timeoutMs 超时时间（毫秒）
         * @return true 表示快照已过期
         */
        public boolean isExpired(long timeoutMs) {
            return getElapsedMillis() > timeoutMs;
        }
    }

    // ========== 数据结构 ==========

    /** 实体快照存储：实体UUID → 快照 */
    private final Map<UUID, EntitySnapshot> snapshots;

    /** 当前被惰性化的实体 UUID 集合 */
    private final Set<UUID> frozenEntities;

    /** 被惰性化实体所在的区块坐标（用于快速查找） */
    private final Map<UUID, BlockPos> entityChunkPositions;

    /** 配置参数 */
    private double freezeRadius;
    private long snapshotTimeoutMs;
    private boolean lazyPassiveEnabled;
    private boolean namedExempt;

    // ========== 统计计数器 ==========

    private long totalSnapshotsCreated = 0;
    private long totalSnapshotsRestored = 0;
    private long totalSnapshotsExpired = 0;
    private long totalTicksSkipped = 0;

    // ========== 构造器 ==========

    public LazyEntityAI(StellarConfig config) {
        this.snapshots = new ConcurrentHashMap<>();
        this.frozenEntities = ConcurrentHashMap.newKeySet();
        this.entityChunkPositions = new ConcurrentHashMap<>();

        this.freezeRadius = config.entityFreezeRadius;
        this.snapshotTimeoutMs = config.entitySnapshotTimeoutMs;
        this.lazyPassiveEnabled = config.entityLazyPassiveEnabled;
        this.namedExempt = config.entityNamedExempt;

        if (config.debugVerboseLogging) {
            LOGGER.info("[LazyEntityAI] 初始化完成。freezeRadius={}m, timeoutMs={}, lazyPassive={}, namedExempt={}",
                freezeRadius, snapshotTimeoutMs, lazyPassiveEnabled, namedExempt);
        }
    }

    // ========== 公共 API：惰性化判定 ==========

    /**
     * 判断一个实体是否应该被惰性化（跳过 AI tick）。
     *
     * @param entity       待判定的实体
     * @param nearestPlayer 最近的玩家（可为 null）
     * @return true 表示应该跳过此实体的 AI tick
     */
    public boolean shouldFreeze(Entity entity, PlayerEntity nearestPlayer) {
        if (entity == null) return false;

        // 玩家实体永不惰性化
        if (entity instanceof PlayerEntity) return false;

        // 已标记为冻结的实体继续冻结
        if (frozenEntities.contains(entity.getUuid())) {
            // 但如果玩家已经足够近，则解冻
            if (nearestPlayer != null && entity.distanceTo(nearestPlayer) < freezeRadius) {
                thawEntity(entity, nearestPlayer);
                return false;
            }
            totalTicksSkipped++;
            return true;
        }

        // 命名实体豁免检查
        if (namedExempt && entity.hasCustomName()) return false;

        // 被动生物豁免检查
        if (!lazyPassiveEnabled && entity instanceof PassiveEntity) return false;

        // 没有玩家引用时，默认不冻结
        if (nearestPlayer == null) return false;

        // 计算距离
        double distance = entity.distanceTo(nearestPlayer);

        // 距离超过冻结半径 → 进入势能态
        if (distance > freezeRadius) {
            freezeEntity(entity, nearestPlayer);
            totalTicksSkipped++;
            return true;
        }

        return false;
    }

    /**
     * 判断一个实体是否应该被惰性化（使用指定位置而非最近玩家）。
     * 用于服务器端没有直接玩家引用时的批量判定。
     *
     * @param entity      待判定的实体
     * @param observerPos 观察者位置（通常是玩家位置）
     * @return true 表示应该跳过此实体的 AI tick
     */
    public boolean shouldFreeze(Entity entity, Vec3d observerPos) {
        if (entity == null || observerPos == null) return false;
        if (entity instanceof PlayerEntity) return false;

        if (frozenEntities.contains(entity.getUuid())) {
            double distance = entity.getPos().distanceTo(observerPos);
            if (distance < freezeRadius) {
                thawEntityAt(entity, observerPos);
                return false;
            }
            totalTicksSkipped++;
            return true;
        }

        if (namedExempt && entity.hasCustomName()) return false;
        if (!lazyPassiveEnabled && entity instanceof PassiveEntity) return false;

        double distance = entity.getPos().distanceTo(observerPos);
        if (distance > freezeRadius) {
            freezeEntityAt(entity, observerPos);
            totalTicksSkipped++;
            return true;
        }

        return false;
    }

    // ========== 公共 API：快照管理 ==========

    /**
     * 为一个实体创建快照并标记为惰性化。
     *
     * @param entity  要冻结的实体
     * @param player  触发冻结的玩家（用于记录上下文）
     */
    public void freezeEntity(Entity entity, PlayerEntity player) {
        if (entity == null) return;

        UUID uuid = entity.getUuid();

        // 创建快照
        EntitySnapshot snapshot = new EntitySnapshot(entity);
        snapshots.put(uuid, snapshot);
        frozenEntities.add(uuid);
        entityChunkPositions.put(uuid, entity.getBlockPos());
        totalSnapshotsCreated++;

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyEntityAI] 实体进入势能态: {} (UUID={}, 距离玩家{:.1f}格)",
                entity.getType().getUntranslatedName(),
                uuid.toString().substring(0, 8),
                entity.distanceTo(player));
        }
    }

    /**
     * 为一个实体创建快照（使用坐标而非玩家对象）。
     */
    public void freezeEntityAt(Entity entity, Vec3d observerPos) {
        if (entity == null) return;

        UUID uuid = entity.getUuid();
        EntitySnapshot snapshot = new EntitySnapshot(entity);
        snapshots.put(uuid, snapshot);
        frozenEntities.add(uuid);
        entityChunkPositions.put(uuid, entity.getBlockPos());
        totalSnapshotsCreated++;
    }

    /**
     * 解冻一个实体并从快照恢复。
     *
     * @param entity  要解冻的实体
     * @param player  触发解冻的玩家
     */
    public void thawEntity(Entity entity, PlayerEntity player) {
        if (entity == null) return;
        restoreFromSnapshot(entity);
    }

    /**
     * 解冻一个实体并从快照恢复（使用坐标）。
     */
    public void thawEntityAt(Entity entity, Vec3d observerPos) {
        if (entity == null) return;
        restoreFromSnapshot(entity);
    }

    /**
     * 从快照恢复实体的状态。
     * 使用"时间跳跃"算法：根据快照时间和速度计算实体当前应该处于的位置和状态。
     *
     * @param entity 要恢复的实体
     */
    public void restoreFromSnapshot(Entity entity) {
        if (entity == null) return;

        UUID uuid = entity.getUuid();
        EntitySnapshot snapshot = snapshots.remove(uuid);

        if (snapshot == null) {
            frozenEntities.remove(uuid);
            entityChunkPositions.remove(uuid);
            return;
        }

        // 验证实体类型是否一致
        if (!snapshot.entityType.equals(entity.getType().getUntranslatedName())) {
            LOGGER.warn("[LazyEntityAI] 实体类型不匹配：快照={}, 当前={}。丢弃快照。",
                snapshot.entityType, entity.getType().getUntranslatedName());
            frozenEntities.remove(uuid);
            entityChunkPositions.remove(uuid);
            totalSnapshotsExpired++;
            return;
        }

        // 计算经过的时间
        long currentWorldTime = entity.getWorld().getTime();
        long elapsedTicks = snapshot.getElapsedTicks(currentWorldTime);

        // 预测当前位置
        Vec3d predictedPos = snapshot.predictPosition(currentWorldTime);

        // 应用预测位置（仅当实体未死亡时）
        if (entity.isAlive() && snapshot.isAlive) {
            // 限制预测位置的合理性（不能移动到世界之外或非法位置）
            World world = entity.getWorld();
            double clampedX = Math.clamp(predictedPos.x,
                -3.0E7, 3.0E7);
            double clampedY = Math.clamp(predictedPos.y,
                world.getBottomY(), world.getTopY());
            double clampedZ = Math.clamp(predictedPos.z,
                -3.0E7, 3.0E7);

            entity.setPosition(clampedX, clampedY, clampedZ);

            // 恢复生命值
            if (entity instanceof LivingEntity living) {
                living.setHealth(snapshot.health);
            }

            // 恢复火焰状态
            if (snapshot.isOnFire && snapshot.fireTicks > 0) {
                entity.setOnFireFor(snapshot.fireTicks / 20); // tick → 秒
            }

            // 恢复角度
            entity.setYaw(snapshot.yaw);
            entity.setPitch(snapshot.pitch);
        }

        // 从冻结集合中移除
        frozenEntities.remove(uuid);
        entityChunkPositions.remove(uuid);
        totalSnapshotsRestored++;

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyEntityAI] 实体从势能态恢复: {} (UUID={}, 经过{}tick, 预测位置[{:.1f},{:.1f},{:.1f}])",
                entity.getType().getUntranslatedName(),
                uuid.toString().substring(0, 8),
                elapsedTicks,
                predictedPos.x, predictedPos.y, predictedPos.z);
        }
    }

    /**
     * 获取实体的快照（如果存在）。
     *
     * @param entity 实体
     * @return 快照，如果实体未被惰性化则返回 null
     */
    public EntitySnapshot getSnapshot(Entity entity) {
        if (entity == null) return null;
        return snapshots.get(entity.getUuid());
    }

    /**
     * 检查实体当前是否被惰性化。
     *
     * @param entity 实体
     * @return true 表示实体处于势能态
     */
    public boolean isFrozen(Entity entity) {
        if (entity == null) return false;
        return frozenEntities.contains(entity.getUuid());
    }

    // ========== 公共 API：快照清理 ==========

    /**
     * 清理过期的快照。
     * 应定期调用（如每 5 秒）以释放不再需要的内存。
     */
    public void cleanExpiredSnapshots() {
        long now = System.currentTimeMillis();
        int expiredCount = 0;

        for (Map.Entry<UUID, EntitySnapshot> entry : snapshots.entrySet()) {
            if (entry.getValue().isExpired(snapshotTimeoutMs)) {
                UUID uuid = entry.getKey();
                snapshots.remove(uuid);
                frozenEntities.remove(uuid);
                entityChunkPositions.remove(uuid);
                expiredCount++;
            }
        }

        if (expiredCount > 0) {
            totalSnapshotsExpired += expiredCount;
            if (StellarCore.getInstance() != null
                && StellarCore.getInstance().getConfig().debugVerboseLogging) {
                LOGGER.info("[LazyEntityAI] 清理了 {} 个过期快照。", expiredCount);
            }
        }
    }

    /**
     * 当实体被移除（死亡、卸载等）时调用，清理关联的快照。
     *
     * @param entity 被移除的实体
     */
    public void onEntityRemoved(Entity entity) {
        if (entity == null) return;
        UUID uuid = entity.getUuid();
        snapshots.remove(uuid);
        frozenEntities.remove(uuid);
        entityChunkPositions.remove(uuid);
    }

    /**
     * 清空所有快照（用于世界卸载或配置重置）。
     */
    public void clearAllSnapshots() {
        int count = snapshots.size();
        snapshots.clear();
        frozenEntities.clear();
        entityChunkPositions.clear();

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyEntityAI] 已清空所有快照。清除了 {} 个快照。", count);
        }
    }

    // ========== 配置更新 ==========

    /**
     * 热更新配置。
     */
    public void updateConfig(StellarConfig config) {
        this.freezeRadius = config.entityFreezeRadius;
        this.snapshotTimeoutMs = config.entitySnapshotTimeoutMs;
        this.lazyPassiveEnabled = config.entityLazyPassiveEnabled;
        this.namedExempt = config.entityNamedExempt;

        if (config.debugVerboseLogging) {
            LOGGER.info("[LazyEntityAI] 配置已更新。freezeRadius={}m, timeoutMs={}, lazyPassive={}, namedExempt={}",
                freezeRadius, snapshotTimeoutMs, lazyPassiveEnabled, namedExempt);
        }
    }

    // ========== 统计查询 ==========

    public long getTotalSnapshotsCreated() { return totalSnapshotsCreated; }
    public long getTotalSnapshotsRestored() { return totalSnapshotsRestored; }
    public long getTotalSnapshotsExpired() { return totalSnapshotsExpired; }
    public long getTotalTicksSkipped() { return totalTicksSkipped; }
    public int getSnapshotCount() { return snapshots.size(); }
    public long getLazyEntityCount() { return frozenEntities.size(); }

    public String getStatisticsSummary() {
        return String.format("快照:%d 冻结实体:%d 创建:%d 恢复:%d 过期:%d 跳过tick:%d",
            snapshots.size(), frozenEntities.size(),
            totalSnapshotsCreated, totalSnapshotsRestored,
            totalSnapshotsExpired, totalTicksSkipped);
    }
}