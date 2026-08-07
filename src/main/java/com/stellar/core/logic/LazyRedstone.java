package com.stellar.core.logic;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class LazyRedstone {

    private static final Logger LOGGER = LoggerFactory.getLogger(StellarCore.MOD_ID);

    // ========== 红石快照数据结构 ==========

    /**
     * 红石快照，存储一个红石网络在冻结时刻的完整状态。
     * 包含所有红石组件的信号强度、传播路径和时间戳。
     * 当玩家重新接近时，使用"时间跳跃"算法快速恢复最终状态。
     */
    public static class RedstoneSnapshot {
        /** 快照唯一标识符 */
        public final long snapshotId;

        /** 快照创建时间（系统毫秒） */
        public final long timestamp;

        /** 快照创建时的世界时间（游戏刻） */
        public final long worldTime;

        /** 红石网络核心位置（信号源位置） */
        public final BlockPos sourcePos;

        /** 红石网络所属区块 */
        public final ChunkPos chunkPos;

        /** 信号传播路径：每个位置 → 该位置的信号强度 */
        public final Map<BlockPos, Integer> signalPath;

        /** 红石组件类型映射：每个位置 → 方块状态字符串（用于恢复） */
        public final Map<BlockPos, String> blockStates;

        /** 网络中所有红石组件的位置（有序，按传播方向排列） */
        public final List<BlockPos> componentOrder;

        /** 该网络是否为红石钟（周期性变化） */
        public final boolean isClock;

        /** 如果是红石钟，记录其周期（tick） */
        public final int clockPeriod;

        /** 如果是红石钟，记录其在周期中的相位（0 ~ period-1） */
        public final int clockPhase;

        /** 快照所属区块中心到最近玩家的距离 */
        public final double distanceToPlayer;

        private static final AtomicLong ID_GENERATOR = new AtomicLong(0);

        public RedstoneSnapshot(BlockPos sourcePos, ChunkPos chunkPos,
                                 Map<BlockPos, Integer> signalPath,
                                 Map<BlockPos, String> blockStates,
                                 List<BlockPos> componentOrder,
                                 boolean isClock, int clockPeriod, int clockPhase,
                                 long worldTime, double distanceToPlayer) {
            this.snapshotId = ID_GENERATOR.incrementAndGet();
            this.timestamp = System.currentTimeMillis();
            this.worldTime = worldTime;
            this.sourcePos = sourcePos.toImmutable();
            this.chunkPos = chunkPos;
            this.signalPath = new HashMap<>(signalPath);
            this.blockStates = new HashMap<>(blockStates);
            this.componentOrder = new ArrayList<>(componentOrder);
            this.isClock = isClock;
            this.clockPeriod = clockPeriod;
            this.clockPhase = clockPhase;
            this.distanceToPlayer = distanceToPlayer;
        }

        /**
         * 获取快照中的组件数量。
         */
        public int getComponentCount() {
            return signalPath.size();
        }

        /**
         * 计算从快照创建到现在经过的游戏刻数。
         */
        public long getElapsedTicks(long currentWorldTime) {
            return Math.max(0, currentWorldTime - worldTime);
        }

        /**
         * 计算红石钟在经过指定 tick 后的相位。
         *
         * @param elapsedTicks 经过的 tick 数
         * @return 当前相位（0 ~ period-1）
         */
        public int calculateClockPhase(long elapsedTicks) {
            if (!isClock || clockPeriod <= 0) return 0;
            return (int) ((clockPhase + elapsedTicks) % clockPeriod);
        }

        /**
         * 计算红石钟在经过指定 tick 后是否应处于激活状态。
         * 假设钟的前半周期为激活，后半周期为非激活。
         */
        public boolean calculateClockActive(long elapsedTicks) {
            if (!isClock || clockPeriod <= 0) {
                return !signalPath.isEmpty() && signalPath.values().stream().anyMatch(s -> s > 0);
            }
            int currentPhase = calculateClockPhase(elapsedTicks);
            return currentPhase < clockPeriod / 2;
        }
    }

    // ========== 数据结构 ==========

    /** 红石快照存储：源位置 → 快照 */
    private final Map<BlockPos, RedstoneSnapshot> snapshots;

    /** 被冻结的区块坐标集合 */
    private final Set<ChunkPos> frozenChunks;

    /** 反向索引：区块坐标 → 该区块内的快照源位置列表 */
    private final Map<ChunkPos, List<BlockPos>> chunkToSnapshots;

    /** 配置参数 */
    private double freezeRadius;
    private int maxSnapshots;
    private boolean clockOptimization;

    // ========== 统计计数器 ==========

    private long totalSnapshotsCreated = 0;
    private long totalSnapshotsRestored = 0;
    private long totalSnapshotsExpired = 0;
    private long totalRedstoneTicksSkipped = 0;

    // ========== 构造器 ==========

    public LazyRedstone(StellarConfig config) {
        this.snapshots = new ConcurrentHashMap<>();
        this.frozenChunks = ConcurrentHashMap.newKeySet();
        this.chunkToSnapshots = new ConcurrentHashMap<>();

        this.freezeRadius = config.redstoneFreezeRadius;
        this.maxSnapshots = config.redstoneMaxSnapshots;
        this.clockOptimization = config.redstoneClockOptimization;

        if (config.debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 初始化完成。freezeRadius={}m, maxSnapshots={}, clockOptim={}",
                freezeRadius, maxSnapshots, clockOptimization);
        }
    }

    // ========== 公共 API：冻结判定 ==========

    /**
     * 判断一个红石组件位置是否应该被惰性化（跳过红石 tick）。
     *
     * @param world      世界对象
     * @param pos        红石组件位置
     * @param playerPos  最近玩家位置
     * @return true 表示应跳过该位置的红石 tick
     */
    public boolean shouldFreeze(ServerWorld world, BlockPos pos, Vec3d playerPos) {
        if (world == null || pos == null || playerPos == null) return false;

        ChunkPos chunkPos = new ChunkPos(pos);

        // 如果该区块已被标记为冻结，检查是否需要解冻
        if (frozenChunks.contains(chunkPos)) {
            double distance = calculateDistance(chunkPos, playerPos);
            if (distance < freezeRadius) {
                thawChunk(world, chunkPos, playerPos);
                return false;
            }
            totalRedstoneTicksSkipped++;
            return true;
        }

        // 检查快照数量限制
        if (snapshots.size() >= maxSnapshots) {
            return false; // 已达上限，不再创建新快照
        }

        // 计算距离
        double distance = calculateDistance(chunkPos, playerPos);

        // 距离超过冻结半径 → 创建快照并冻结
        if (distance > freezeRadius) {
            freezeChunk(world, chunkPos, playerPos, distance);
            totalRedstoneTicksSkipped++;
            return true;
        }

        return false;
    }

    /**
     * 判断一个区块的红石是否应被惰性化（批量判定版本）。
     *
     * @param chunk      区块
     * @param playerPos  最近玩家位置
     * @return true 表示应跳过该区块的红石 tick
     */
    public boolean shouldFreezeChunk(WorldChunk chunk, Vec3d playerPos) {
        if (chunk == null || playerPos == null) return false;

        ChunkPos chunkPos = chunk.getPos();

        if (frozenChunks.contains(chunkPos)) {
            double distance = calculateDistance(chunkPos, playerPos);
            if (distance < freezeRadius) {
                // 需要解冻，但批量版本不在此处解冻（由单点版本负责）
                return false;
            }
            return true;
        }

        double distance = calculateDistance(chunkPos, playerPos);
        return distance > freezeRadius;
    }

    // ========== 公共 API：快照管理 ==========

    /**
     * 冻结一个区块的红石电路。
     * 扫描区块内所有红石组件，构建信号传播路径并创建快照。
     *
     * @param world          世界对象
     * @param chunkPos       区块坐标
     * @param playerPos      玩家位置
     * @param distanceToPlayer 到玩家的距离
     */
    public void freezeChunk(ServerWorld world, ChunkPos chunkPos, Vec3d playerPos, double distanceToPlayer) {
        if (world == null || chunkPos == null) return;

        // 检查快照数量限制
        if (snapshots.size() >= maxSnapshots) {
            return;
        }

        // 找到该区块内所有红石信号源
        List<BlockPos> sources = findRedstoneSources(world, chunkPos);
        if (sources.isEmpty()) {
            // 没有红石组件，但仍标记为冻结以避免重复扫描
            frozenChunks.add(chunkPos);
            return;
        }

        // 为每个信号源创建快照
        List<BlockPos> sourceList = new ArrayList<>();
        for (BlockPos sourcePos : sources) {
            if (snapshots.size() >= maxSnapshots) break;

            RedstoneSnapshot snapshot = buildSnapshot(world, sourcePos, chunkPos, distanceToPlayer);
            if (snapshot != null) {
                snapshots.put(sourcePos.toImmutable(), snapshot);
                sourceList.add(sourcePos.toImmutable());
                totalSnapshotsCreated++;
            }
        }

        // 更新索引
        frozenChunks.add(chunkPos);
        if (!sourceList.isEmpty()) {
            chunkToSnapshots.put(chunkPos, sourceList);
        }

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 冻结区块红石: {} ({}个网络, 距离{:.1f}格)",
                chunkPos, sourceList.size(), distanceToPlayer);
        }
    }

    /**
     * 解冻一个区块的红石电路，从快照恢复。
     *
     * @param world      世界对象
     * @param chunkPos   区块坐标
     * @param playerPos  玩家位置
     */
    public void thawChunk(ServerWorld world, ChunkPos chunkPos, Vec3d playerPos) {
        if (world == null || chunkPos == null) return;

        List<BlockPos> sourceList = chunkToSnapshots.remove(chunkPos);
        frozenChunks.remove(chunkPos);

        if (sourceList == null || sourceList.isEmpty()) {
            return; // 没有快照需要恢复
        }

        int restoredCount = 0;
        for (BlockPos sourcePos : sourceList) {
            RedstoneSnapshot snapshot = snapshots.remove(sourcePos);
            if (snapshot != null) {
                restoreFromSnapshot(world, snapshot);
                restoredCount++;
                totalSnapshotsRestored++;
            }
        }

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 解冻区块红石: {} (恢复了{}个网络)",
                chunkPos, restoredCount);
        }
    }

    /**
     * 从快照恢复红石电路状态。
     * 使用"时间跳跃"算法：根据经过的 tick 数和快照信息，
     * 直接计算红石电路的最终状态，跳过中间所有 tick。
     *
     * @param world    世界对象
     * @param snapshot 红石快照
     */
    public void restoreFromSnapshot(ServerWorld world, RedstoneSnapshot snapshot) {
        if (world == null || snapshot == null) return;

        long currentWorldTime = world.getTime();
        long elapsedTicks = snapshot.getElapsedTicks(currentWorldTime);

        // 对于红石钟，使用相位计算快速恢复
        if (snapshot.isClock && clockOptimization) {
            boolean clockActive = snapshot.calculateClockActive(elapsedTicks);
            int currentPhase = snapshot.calculateClockPhase(elapsedTicks);

            // 恢复红石钟状态
            restoreClockState(world, snapshot, clockActive, currentPhase);
            return;
        }

        // 对于非钟网络：红石信号在玩家离开期间可能发生了衰减变化
        // 但如果没有新的信号源，信号在无人观测时保持稳定
        // 因此直接恢复快照中的信号强度即可
        for (Map.Entry<BlockPos, Integer> entry : snapshot.signalPath.entrySet()) {
            BlockPos pos = entry.getKey();
            int signalStrength = entry.getValue();

            // 验证该位置仍然存在且是红石组件
            BlockState currentState = world.getBlockState(pos);
            if (currentState.isOf(Blocks.REDSTONE_WIRE)) {
                world.setBlockState(pos, currentState.with(RedstoneWireBlock.POWER, signalStrength),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
            } else if (currentState.isOf(Blocks.REDSTONE_WALL_TORCH)
                       || currentState.isOf(Blocks.REDSTONE_TORCH)) {
                boolean lit = signalStrength > 0;
                if (currentState.contains(Properties.LIT)) {
                    world.setBlockState(pos, currentState.with(Properties.LIT, lit),
                        net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
                }
            } else if (currentState.isOf(Blocks.REPEATER)) {
                boolean powered = signalStrength > 0;
                if (currentState.contains(RepeaterBlock.POWERED)) {
                    world.setBlockState(pos, currentState.with(RepeaterBlock.POWERED, powered),
                        net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
                }
            }
        }

        // 标记恢复后的红石网络需要通知相邻区块
        if (!snapshot.componentOrder.isEmpty()) {
            BlockPos firstPos = snapshot.componentOrder.get(0);
            world.updateNeighborsAlways(firstPos, world.getBlockState(firstPos).getBlock());
        }

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 从快照恢复红石网络: source={}, 组件数={}, 经过{}tick",
                snapshot.sourcePos, snapshot.getComponentCount(), elapsedTicks);
        }
    }

    /**
     * 获取指定位置的快照（如果存在）。
     */
    public RedstoneSnapshot getSnapshot(BlockPos sourcePos) {
        return snapshots.get(sourcePos);
    }

    /**
     * 检查指定区块的红石是否被冻结。
     */
    public boolean isChunkFrozen(ChunkPos chunkPos) {
        return frozenChunks.contains(chunkPos);
    }

    // ========== 公共 API：快照清理 ==========

    /**
     * 清理过期快照（基于最大快照数量）。
     * 当快照数量超过限制时，移除最旧的快照。
     */
    public void cleanExpiredSnapshots() {
        if (snapshots.size() <= maxSnapshots) return;

        // 按时间戳排序，移除最旧的
        int toRemove = snapshots.size() - maxSnapshots;
        List<Map.Entry<BlockPos, RedstoneSnapshot>> sortedEntries = new ArrayList<>(snapshots.entrySet());
        sortedEntries.sort((a, b) -> Long.compare(a.getValue().timestamp, b.getValue().timestamp));

        for (int i = 0; i < toRemove && i < sortedEntries.size(); i++) {
            BlockPos pos = sortedEntries.get(i).getKey();
            RedstoneSnapshot removed = snapshots.remove(pos);
            if (removed != null) {
                totalSnapshotsExpired++;
                // 清理反向索引
                List<BlockPos> sourceList = chunkToSnapshots.get(removed.chunkPos);
                if (sourceList != null) {
                    sourceList.remove(pos);
                    if (sourceList.isEmpty()) {
                        chunkToSnapshots.remove(removed.chunkPos);
                        frozenChunks.remove(removed.chunkPos);
                    }
                }
            }
        }

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 清理了 {} 个过期快照。当前快照数: {}",
                toRemove, snapshots.size());
        }
    }

    /**
     * 区块卸载时清理关联快照。
     */
    public void onChunkUnload(ChunkPos chunkPos) {
        List<BlockPos> sourceList = chunkToSnapshots.remove(chunkPos);
        frozenChunks.remove(chunkPos);

        if (sourceList != null) {
            for (BlockPos sourcePos : sourceList) {
                snapshots.remove(sourcePos);
            }
        }
    }

    /**
     * 清空所有快照。
     */
    public void clearAllSnapshots() {
        int count = snapshots.size();
        snapshots.clear();
        frozenChunks.clear();
        chunkToSnapshots.clear();

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 已清空所有快照。清除了 {} 个网络。", count);
        }
    }

    // ========== 配置更新 ==========

    public void updateConfig(StellarConfig config) {
        this.freezeRadius = config.redstoneFreezeRadius;
        this.maxSnapshots = config.redstoneMaxSnapshots;
        this.clockOptimization = config.redstoneClockOptimization;

        if (config.debugVerboseLogging) {
            LOGGER.info("[LazyRedstone] 配置已更新。freezeRadius={}m, maxSnapshots={}, clockOptim={}",
                freezeRadius, maxSnapshots, clockOptimization);
        }
    }

    // ========== 统计查询 ==========

    public long getSnapshotCount() { return snapshots.size(); }
    public int getFrozenChunkCount() { return frozenChunks.size(); }
    public long getTotalSnapshotsCreated() { return totalSnapshotsCreated; }
    public long getTotalSnapshotsRestored() { return totalSnapshotsRestored; }
    public long getTotalRedstoneTicksSkipped() { return totalRedstoneTicksSkipped; }

    public String getStatisticsSummary() {
        return String.format("快照:%d 冻结区块:%d 创建:%d 恢复:%d 跳过tick:%d",
            snapshots.size(), frozenChunks.size(),
            totalSnapshotsCreated, totalSnapshotsRestored, totalRedstoneTicksSkipped);
    }

    // ========== 内部方法 ==========

    /**
     * 计算区块中心到玩家位置的距离。
     */
    private double calculateDistance(ChunkPos chunkPos, Vec3d playerPos) {
        double centerX = chunkPos.getStartX() + 8.0;
        double centerZ = chunkPos.getStartZ() + 8.0;
        double dx = centerX - playerPos.x;
        double dz = centerZ - playerPos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 寻找区块内所有红石信号源。
     * 信号源包括：红石火把、红石块、拉杆、按钮、压力板、阳光传感器等。
     */
    private List<BlockPos> findRedstoneSources(ServerWorld world, ChunkPos chunkPos) {
        List<BlockPos> sources = new ArrayList<>();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = world.getBottomY(); y < world.getTopY(); y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    BlockState state = world.getBlockState(pos);

                    if (isRedstoneSource(state)) {
                        sources.add(pos.toImmutable());
                    }
                }
            }
        }
        return sources;
    }

    /**
     * 判断方块状态是否为红石信号源。
     */
    private boolean isRedstoneSource(BlockState state) {
        if (state == null || state.isAir()) return false;

        // 红石火把
        if (state.isOf(Blocks.REDSTONE_TORCH) || state.isOf(Blocks.REDSTONE_WALL_TORCH)) {
            return true;
        }
        // 红石块
        if (state.isOf(Blocks.REDSTONE_BLOCK)) return true;
        // 拉杆
        if (state.isOf(Blocks.LEVER)) return true;
        // 按钮
        if (state.isOf(Blocks.STONE_BUTTON) || state.isOf(Blocks.OAK_BUTTON)
            || state.isOf(Blocks.SPRUCE_BUTTON) || state.isOf(Blocks.BIRCH_BUTTON)
            || state.isOf(Blocks.JUNGLE_BUTTON) || state.isOf(Blocks.ACACIA_BUTTON)
            || state.isOf(Blocks.DARK_OAK_BUTTON) || state.isOf(Blocks.CRIMSON_BUTTON)
            || state.isOf(Blocks.WARPED_BUTTON) || state.isOf(Blocks.MANGROVE_BUTTON)
            || state.isOf(Blocks.BAMBOO_BUTTON) || state.isOf(Blocks.CHERRY_BUTTON)
            || state.isOf(Blocks.POLISHED_BLACKSTONE_BUTTON)) {
            return true;
        }
        // 压力板
        if (state.isOf(Blocks.STONE_PRESSURE_PLATE) || state.isOf(Blocks.OAK_PRESSURE_PLATE)
            || state.isOf(Blocks.SPRUCE_PRESSURE_PLATE) || state.isOf(Blocks.BIRCH_PRESSURE_PLATE)
            || state.isOf(Blocks.JUNGLE_PRESSURE_PLATE) || state.isOf(Blocks.ACACIA_PRESSURE_PLATE)
            || state.isOf(Blocks.DARK_OAK_PRESSURE_PLATE) || state.isOf(Blocks.CRIMSON_PRESSURE_PLATE)
            || state.isOf(Blocks.WARPED_PRESSURE_PLATE) || state.isOf(Blocks.MANGROVE_PRESSURE_PLATE)
            || state.isOf(Blocks.BAMBOO_PRESSURE_PLATE) || state.isOf(Blocks.CHERRY_PRESSURE_PLATE)
            || state.isOf(Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE)
            || state.isOf(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE)
            || state.isOf(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE)) {
            return true;
        }
        // 阳光传感器
        if (state.isOf(Blocks.DAYLIGHT_DETECTOR)) return true;
        // 侦测器（面向特定方向时是信号源）
        if (state.isOf(Blocks.OBSERVER)) return true;
        // 绊线钩
        if (state.isOf(Blocks.TRIPWIRE_HOOK)) return true;

        return false;
    }

    /**
     * 构建红石网络的快照。
     * 从信号源出发，使用 BFS 沿红石线传播，记录每个位置的信号强度。
     */
    private RedstoneSnapshot buildSnapshot(ServerWorld world, BlockPos sourcePos,
                                            ChunkPos chunkPos, double distanceToPlayer) {
        Map<BlockPos, Integer> signalPath = new HashMap<>();
        Map<BlockPos, String> blockStates = new HashMap<>();
        List<BlockPos> componentOrder = new ArrayList<>();

        // BFS 传播
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();

        BlockState sourceState = world.getBlockState(sourcePos);
        int sourceSignal = getSignalStrength(world, sourcePos, sourceState);

        if (sourceSignal <= 0) return null;

        queue.add(sourcePos);
        visited.add(sourcePos);
        signalPath.put(sourcePos.toImmutable(), sourceSignal);
        blockStates.put(sourcePos.toImmutable(), sourceState.toString());
        componentOrder.add(sourcePos.toImmutable());

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int currentSignal = signalPath.getOrDefault(current, 0);

            if (currentSignal <= 1) continue; // 信号强度为1时不再传播

            // 向四个水平方向传播
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos next = current.offset(dir);
                if (visited.contains(next)) continue;
                if (!isWithinChunk(next, chunkPos)) continue; // 只记录当前区块内的连接

                BlockState nextState = world.getBlockState(next);
                if (!isRedstoneComponent(nextState)) continue;

                visited.add(next);
                int nextSignal = currentSignal - 1;
                signalPath.put(next.toImmutable(), nextSignal);
                blockStates.put(next.toImmutable(), nextState.toString());
                componentOrder.add(next.toImmutable());
                queue.add(next);
            }

            // 检查上方和下方（红石线可以上下连接）
            for (Direction dir : new Direction[]{Direction.UP, Direction.DOWN}) {
                BlockPos next = current.offset(dir);
                if (visited.contains(next)) continue;
                if (!isWithinChunk(next, chunkPos)) continue;

                BlockState nextState = world.getBlockState(next);
                if (nextState.isOf(Blocks.REDSTONE_WIRE)) {
                    visited.add(next);
                    int nextSignal = currentSignal - 1;
                    signalPath.put(next.toImmutable(), nextSignal);
                    blockStates.put(next.toImmutable(), nextState.toString());
                    componentOrder.add(next.toImmutable());
                    queue.add(next);
                }
            }
        }

        // 检测是否为红石钟
        boolean isClock = false;
        int clockPeriod = 0;
        int clockPhase = 0;

        if (clockOptimization) {
            ClockDetectionResult clockResult = detectClock(world, signalPath, sourcePos);
            isClock = clockResult.isClock;
            clockPeriod = clockResult.period;
            clockPhase = clockResult.phase;
        }

        return new RedstoneSnapshot(sourcePos, chunkPos, signalPath, blockStates,
            componentOrder, isClock, clockPeriod, clockPhase,
            world.getTime(), distanceToPlayer);
    }

    /**
     * 检测红石网络是否为红石钟。
     */
    private ClockDetectionResult detectClock(ServerWorld world,
                                               Map<BlockPos, Integer> signalPath,
                                               BlockPos sourcePos) {
        // 简化检测：检查是否存在比较器 + 红石线的环状结构
        for (BlockPos pos : signalPath.keySet()) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.COMPARATOR)) {
                // 比较器在减法模式下可能构成钟
                if (state.contains(ComparatorBlock.MODE)
                    && state.get(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT) {
                    return new ClockDetectionResult(true, 4, 0); // 4 tick 周期
                }
            }
            if (state.isOf(Blocks.REPEATER)) {
                // 中继器环可能构成钟
                return new ClockDetectionResult(true, 4, 0);
            }
        }

        return new ClockDetectionResult(false, 0, 0);
    }

    /**
     * 红石钟检测结果。
     */
    private static class ClockDetectionResult {
        final boolean isClock;
        final int period;
        final int phase;

        ClockDetectionResult(boolean isClock, int period, int phase) {
            this.isClock = isClock;
            this.period = period;
            this.phase = phase;
        }
    }

    /**
     * 恢复红石钟状态。
     */
    private void restoreClockState(ServerWorld world, RedstoneSnapshot snapshot,
                                    boolean clockActive, int currentPhase) {
        // 红石钟的恢复：根据相位设置激活/非激活状态
        for (Map.Entry<BlockPos, Integer> entry : snapshot.signalPath.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState currentState = world.getBlockState(pos);

            int signalToSet = clockActive ? 15 : 0;

            if (currentState.isOf(Blocks.REDSTONE_WIRE)) {
                world.setBlockState(pos, currentState.with(RedstoneWireBlock.POWER, signalToSet),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
            } else if (currentState.contains(Properties.LIT)) {
                world.setBlockState(pos, currentState.with(Properties.LIT, clockActive),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
            } else if (currentState.contains(Properties.POWERED)) {
                world.setBlockState(pos, currentState.with(Properties.POWERED, clockActive),
                    net.minecraft.block.Block.NOTIFY_LISTENERS | 2);
            }
        }

        if (!snapshot.componentOrder.isEmpty()) {
            BlockPos firstPos = snapshot.componentOrder.get(0);
            world.updateNeighborsAlways(firstPos, world.getBlockState(firstPos).getBlock());
        }
    }

    /**
     * 获取方块的红石信号强度。
     */
    private int getSignalStrength(World world, BlockPos pos, BlockState state) {
        if (state == null) return 0;

        // 红石火把：点亮时为15
        if ((state.isOf(Blocks.REDSTONE_TORCH) || state.isOf(Blocks.REDSTONE_WALL_TORCH))
            && state.contains(Properties.LIT)) {
            return state.get(Properties.LIT) ? 15 : 0;
        }
        // 红石块：恒为15
        if (state.isOf(Blocks.REDSTONE_BLOCK)) return 15;
        // 拉杆：激活时为15
        if (state.isOf(Blocks.LEVER) && state.contains(Properties.POWERED)) {
            return state.get(Properties.POWERED) ? 15 : 0;
        }
        // 按钮：激活时为15
        if (state.contains(Properties.POWERED)) {
            return state.get(Properties.POWERED) ? 15 : 0;
        }
        // 压力板：根据实体数量
        if (state.contains(Properties.POWER)) {
            return state.get(Properties.POWER);
        }
        // 阳光传感器
        if (state.isOf(Blocks.DAYLIGHT_DETECTOR) && state.contains(Properties.POWER)) {
            return state.get(Properties.POWER);
        }
        // 侦测器
        if (state.isOf(Blocks.OBSERVER) && state.contains(Properties.POWERED)) {
            return state.get(Properties.POWERED) ? 15 : 0;
        }
        // 红石线：读取当前 POWER 属性
        if (state.isOf(Blocks.REDSTONE_WIRE) && state.contains(RedstoneWireBlock.POWER)) {
            return state.get(RedstoneWireBlock.POWER);
        }

        return 0;
    }

    /**
     * 判断方块是否为红石组件。
     */
    private boolean isRedstoneComponent(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.isOf(Blocks.REDSTONE_WIRE)
            || state.isOf(Blocks.REDSTONE_TORCH)
            || state.isOf(Blocks.REDSTONE_WALL_TORCH)
            || state.isOf(Blocks.REPEATER)
            || state.isOf(Blocks.COMPARATOR)
            || state.isOf(Blocks.REDSTONE_BLOCK)
            || state.isOf(Blocks.LEVER)
            || state.isOf(Blocks.OBSERVER);
    }

    /**
     * 判断位置是否在指定区块内。
     */
    private boolean isWithinChunk(BlockPos pos, ChunkPos chunkPos) {
        return pos.getX() >= chunkPos.getStartX()
            && pos.getX() <= chunkPos.getEndX()
            && pos.getZ() >= chunkPos.getStartZ()
            && pos.getZ() <= chunkPos.getEndZ();
    }
}