package com.stellar.core.logic;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== 区块状态枚举 ==========

    /**
     * 区块的四种模拟状态。
     *
     * ACTIVE    — 玩家身边（< activeRadius 格），全模拟：实体AI、红石、流体、随机刻全部运行。
     * IDLE      — 玩家附近（activeRadius ~ idleRadius 格），仅关键逻辑：红石、流体继续运行，实体AI降频。
     * FROZEN    — 远处（idleRadius ~ frozenRadius 格），完全冻结：不进行任何 tick，仅保留方块状态快照。
     * POTENTIAL — 超远处（> frozenRadius 格），仅存储"如何重建"的信息（种子+坐标），在玩家接近时按需生成。
     */
    public enum ChunkState {
        /** 完全模拟 */
        ACTIVE,
        /** 仅关键逻辑（红石/流体），实体AI降频 */
        IDLE,
        /** 完全冻结，无 tick */
        FROZEN,
        /** 仅存重建信息（种子+坐标） */
        POTENTIAL
    }

    // ========== 数据结构 ==========

    /** 区块状态映射：ChunkPos → ChunkState */
    private final Map<ChunkPos, ChunkState> stateMap;

    /** 反向索引：每种状态包含哪些区块 */
    private final Map<ChunkState, Set<ChunkPos>> stateIndex;

    /** 状态转移计数器（用于统计） */
    private final Map<ChunkState, AtomicLong> stateCounters;

    /** 状态转移历史记录（最近1000条，用于调试） */
    private final List<StateTransition> transitionHistory;
    private static final int MAX_TRANSITION_HISTORY = 1000;

    /** 状态半径配置 */
    private double activeRadius;
    private double idleRadius;
    private double frozenRadius;
    private boolean potentialEnabled;

    /** 更新间隔（tick），避免每 tick 都重新计算所有区块状态 */
    private static final int UPDATE_INTERVAL_TICKS = 20; // 每秒更新一次
    private int tickCounter = 0;

    // ========== 状态转移记录 ==========

    /**
     * 记录一次状态转移，用于调试和性能分析。
     */
    public static class StateTransition {
        public final ChunkPos pos;
        public final ChunkState from;
        public final ChunkState to;
        public final long timestamp;
        public final double distance;

        public StateTransition(ChunkPos pos, ChunkState from, ChunkState to, double distance) {
            this.pos = pos;
            this.from = from;
            this.to = to;
            this.timestamp = System.currentTimeMillis();
            this.distance = distance;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: %s → %s (距离:%.1f格)",
                new java.util.Date(timestamp), pos, from, to, distance);
        }
    }

    // ========== 构造器 ==========

    public ChunkStateManager(StellarConfig config) {
        this.stateMap = new ConcurrentHashMap<>();
        this.stateIndex = new EnumMap<>(ChunkState.class);
        this.stateCounters = new EnumMap<>(ChunkState.class);
        this.transitionHistory = new ArrayList<>();

        // 初始化反向索引
        for (ChunkState state : ChunkState.values()) {
            stateIndex.put(state, ConcurrentHashMap.newKeySet());
            stateCounters.put(state, new AtomicLong(0));
        }

        this.activeRadius = config.chunkActiveRadius;
        this.idleRadius = config.chunkIdleRadius;
        this.frozenRadius = config.chunkFrozenRadius;
        this.potentialEnabled = config.chunkPotentialEnabled;

        if (config.debugVerboseLogging) {
            LOGGER.info("[ChunkStateManager] 初始化完成。ACTIVE<{}m, IDLE<{}m, FROZEN<{}m, POTENTIAL={}",
                activeRadius, idleRadius, frozenRadius, potentialEnabled ? "启用" : "禁用");
        }
    }

    // ========== 公共 API：状态查询 ==========

    /**
     * 获取区块的当前状态。
     *
     * @param pos 区块坐标
     * @return 当前状态，如果未记录则返回 ACTIVE（安全默认值）
     */
    public ChunkState getState(ChunkPos pos) {
        ChunkState state = stateMap.get(pos);
        return state != null ? state : ChunkState.ACTIVE;
    }

    /**
     * 判断区块是否处于 ACTIVE 状态（全模拟）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块全模拟
     */
    public boolean isActive(ChunkPos pos) {
        return getState(pos) == ChunkState.ACTIVE;
    }

    /**
     * 判断区块是否应该被 tick（仅 ACTIVE 和 IDLE 状态需要 tick）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块需要 tick
     */
    public boolean shouldTick(ChunkPos pos) {
        ChunkState state = getState(pos);
        return state == ChunkState.ACTIVE || state == ChunkState.IDLE;
    }

    /**
     * 判断区块是否应该执行实体 AI（仅 ACTIVE 状态执行完整 AI）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块的实体应执行完整 AI
     */
    public boolean shouldRunEntityAI(ChunkPos pos) {
        return getState(pos) == ChunkState.ACTIVE;
    }

    /**
     * 判断区块是否应该处理红石更新（ACTIVE 和 IDLE 状态）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块应处理红石
     */
    public boolean shouldProcessRedstone(ChunkPos pos) {
        ChunkState state = getState(pos);
        return state == ChunkState.ACTIVE || state == ChunkState.IDLE;
    }

    /**
     * 判断区块是否应该处理流体扩散（ACTIVE 和 IDLE 状态）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块应处理流体
     */
    public boolean shouldProcessFluid(ChunkPos pos) {
        ChunkState state = getState(pos);
        return state == ChunkState.ACTIVE || state == ChunkState.IDLE;
    }

    /**
     * 判断区块是否完全冻结（FROZEN 或 POTENTIAL 状态）。
     *
     * @param pos 区块坐标
     * @return true 表示该区块被冻结
     */
    public boolean isFrozen(ChunkPos pos) {
        ChunkState state = getState(pos);
        return state == ChunkState.FROZEN || state == ChunkState.POTENTIAL;
    }

    // ========== 公共 API：状态更新 ==========

    /**
     * 基于玩家位置更新单个区块的状态。
     *
     * @param pos        区块坐标
     * @param playerPos  玩家位置
     * @return 更新后的状态；如果状态未变化返回 null
     */
    public ChunkState updateState(ChunkPos pos, Vec3d playerPos) {
        if (pos == null || playerPos == null) return null;

        double distance = calculateDistance(pos, playerPos);
        ChunkState newState = calculateState(distance);
        ChunkState oldState = stateMap.get(pos);

        if (oldState == newState) {
            return null; // 状态未变化
        }

        // 执行状态转移
        stateMap.put(pos, newState);

        // 更新反向索引
        if (oldState != null) {
            stateIndex.get(oldState).remove(pos);
            stateCounters.get(oldState).decrementAndGet();
        }
        stateIndex.get(newState).add(pos);
        stateCounters.get(newState).incrementAndGet();

        // 记录转移历史
        recordTransition(pos, oldState, newState, distance);

        return newState;
    }

    /**
     * 基于玩家位置批量更新所有已加载区块的状态。
     * 每秒调用一次（每 20 tick），避免高频计算。
     *
     * @param world     服务器世界
     * @param playerPos 玩家位置
     */
    public void updateAllStates(ServerWorld world, Vec3d playerPos) {
        if (world == null || playerPos == null) return;

        tickCounter++;
        if (tickCounter % UPDATE_INTERVAL_TICKS != 0) {
            return; // 未到更新间隔，跳过
        }

        // 遍历所有已加载区块
        for (WorldChunk chunk : world.getChunkManager().getLoadedChunks()) {
            ChunkPos pos = chunk.getPos();
            updateState(pos, playerPos);
        }

        // 清理 POTENTIAL 状态的区块（这些区块不应保留在已加载列表中）
        cleanupPotentialChunks();
    }

    /**
     * 当玩家移动时更新区块状态。
     * 此方法比 updateAllStates 更轻量，适用于高频调用。
     *
     * @param playerPos    玩家当前位置
     * @param prevPlayerPos 玩家上一 tick 位置
     */
    public void onPlayerMove(Vec3d playerPos, Vec3d prevPlayerPos) {
        if (playerPos == null) return;

        // 仅当玩家移动超过 8 格时才重新计算
        if (prevPlayerPos != null) {
            double dx = playerPos.x - prevPlayerPos.x;
            double dy = playerPos.y - prevPlayerPos.y;
            double dz = playerPos.z - prevPlayerPos.z;
            double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (moved < 8.0) {
                return; // 移动距离太小，不需要更新
            }
        }

        // 遍历所有已记录区块，更新状态
        for (Map.Entry<ChunkPos, ChunkState> entry : stateMap.entrySet()) {
            updateState(entry.getKey(), playerPos);
        }
    }

    // ========== 公共 API：区块生命周期 ==========

    /**
     * 区块加载时调用。
     *
     * @param pos   区块坐标
     * @param world 世界对象
     */
    public void onChunkLoad(ChunkPos pos, ServerWorld world) {
        if (pos == null) return;

        // 计算初始状态（使用世界出生点作为默认参考位置）
        Vec3d spawnPos = Vec3d.ofCenter(world.getSpawnPos());
        ChunkState initialState = updateState(pos, spawnPos);
        if (initialState == null) {
            // 状态未变化，手动设置为默认状态
            initialState = ChunkState.ACTIVE;
            stateMap.put(pos, initialState);
            stateIndex.get(initialState).add(pos);
            stateCounters.get(initialState).incrementAndGet();
        }
    }

    /**
     * 区块卸载时调用。
     *
     * @param pos 区块坐标
     */
    public void onChunkUnload(ChunkPos pos) {
        if (pos == null) return;

        ChunkState oldState = stateMap.remove(pos);
        if (oldState != null) {
            stateIndex.get(oldState).remove(pos);
            stateCounters.get(oldState).decrementAndGet();
        }
    }

    // ========== 公共 API：统计与调试 ==========

    /**
     * 获取指定状态的区块数量。
     *
     * @param state 区块状态
     * @return 区块数量
     */
    public long getStateCount(ChunkState state) {
        AtomicLong counter = stateCounters.get(state);
        return counter != null ? counter.get() : 0;
    }

    /**
     * 获取冻结区块数量（FROZEN + POTENTIAL）。
     *
     * @return 冻结区块总数
     */
    public long getFrozenChunkCount() {
        return getStateCount(ChunkState.FROZEN) + getStateCount(ChunkState.POTENTIAL);
    }

    /**
     * 获取指定状态下的所有区块坐标列表。
     *
     * @param state 区块状态
     * @return 区块坐标集合
     */
    public Set<ChunkPos> getChunksInState(ChunkState state) {
        return stateIndex.getOrDefault(state, ConcurrentHashMap.newKeySet());
    }

    /**
     * 获取所有已记录区块的总数。
     *
     * @return 区块总数
     */
    public int getTotalTrackedChunks() {
        return stateMap.size();
    }

    /**
     * 获取最近的若干条状态转移记录。
     *
     * @param count 记录数量
     * @return 转移记录列表
     */
    public List<StateTransition> getRecentTransitions(int count) {
        synchronized (transitionHistory) {
            int size = transitionHistory.size();
            if (size <= count) {
                return new ArrayList<>(transitionHistory);
            }
            return new ArrayList<>(transitionHistory.subList(size - count, size));
        }
    }

    /**
     * 获取状态分布摘要（调试用）。
     *
     * @return 格式化的状态分布字符串
     */
    public String getDistributionSummary() {
        return String.format("ACTIVE=%d IDLE=%d FROZEN=%d POTENTIAL=%d (总计:%d)",
            getStateCount(ChunkState.ACTIVE),
            getStateCount(ChunkState.IDLE),
            getStateCount(ChunkState.FROZEN),
            getStateCount(ChunkState.POTENTIAL),
            getTotalTrackedChunks());
    }

    // ========== 配置更新 ==========

    /**
     * 热更新配置。
     */
    public void updateConfig(StellarConfig config) {
        this.activeRadius = config.chunkActiveRadius;
        this.idleRadius = config.chunkIdleRadius;
        this.frozenRadius = config.chunkFrozenRadius;
        this.potentialEnabled = config.chunkPotentialEnabled;

        if (config.debugVerboseLogging) {
            LOGGER.info("[ChunkStateManager] 配置已更新。ACTIVE<{}m, IDLE<{}m, FROZEN<{}m, POTENTIAL={}",
                activeRadius, idleRadius, frozenRadius, potentialEnabled ? "启用" : "禁用");
        }

        // 注意：配置变更后现有区块状态不会立即重新计算
        // 下一次 updateAllStates() 或 onPlayerMove() 调用时自动修正
    }

    // ========== 内部方法 ==========

    /**
     * 计算区块中心到玩家位置的距离。
     */
    private double calculateDistance(ChunkPos pos, Vec3d playerPos) {
        double centerX = pos.getStartX() + 8.0;
        double centerY = 128.0;
        double centerZ = pos.getStartZ() + 8.0;

        double dx = centerX - playerPos.x;
        double dy = centerY - playerPos.y;
        double dz = centerZ - playerPos.z;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 根据距离计算区块应处于的状态。
     */
    private ChunkState calculateState(double distance) {
        if (distance < activeRadius) {
            return ChunkState.ACTIVE;
        } else if (distance < idleRadius) {
            return ChunkState.IDLE;
        } else if (distance < frozenRadius) {
            return ChunkState.FROZEN;
        } else {
            return potentialEnabled ? ChunkState.POTENTIAL : ChunkState.FROZEN;
        }
    }

    /**
     * 记录状态转移。
     */
    private void recordTransition(ChunkPos pos, ChunkState from, ChunkState to, double distance) {
        StateTransition transition = new StateTransition(pos, from, to, distance);
        synchronized (transitionHistory) {
            transitionHistory.add(transition);
            // 保持历史记录大小
            while (transitionHistory.size() > MAX_TRANSITION_HISTORY) {
                transitionHistory.remove(0);
            }
        }

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[ChunkStateManager] 状态转移: {}", transition);
        }
    }

    /**
     * 清理 POTENTIAL 状态的区块。
     * 这些区块理论上不应保留在已加载列表中，如果存在则移除。
     */
    private void cleanupPotentialChunks() {
        Set<ChunkPos> potentialChunks = stateIndex.get(ChunkState.POTENTIAL);
        for (ChunkPos pos : potentialChunks) {
            stateMap.remove(pos);
        }
        stateCounters.get(ChunkState.POTENTIAL).set(0);
        potentialChunks.clear();
    }
}