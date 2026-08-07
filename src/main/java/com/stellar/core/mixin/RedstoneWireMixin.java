package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;
import com.stellar.core.logic.ChunkStateManager;
import com.stellar.core.logic.LazyRedstone;
import com.stellar.core.logic.ChunkStateManager.ChunkState;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneWireMixin extends Block {

    /**
     * 强制构造函数（Mixin 要求，不会实际调用）
     */
    public RedstoneWireMixin(Settings settings) {
        super(settings);
    }

    @Shadow
    protected abstract BlockState getPlacementState(World world, BlockState state, BlockPos pos);

    @Shadow
    private void update(World world, BlockPos pos, BlockState state) {}

    @Shadow
    protected abstract int getReceivedRedstonePower(World world, BlockPos pos);

    // ========== 内部状态 ==========

    @Unique
    private static final int LAZY_CHECK_INTERVAL = 10; // 每 10 次红石更新才检查一次惰性化

    @Unique
    private int stellarCore_updateCounter = 0;

    @Unique
    private Vec3d stellarCore_cachedPlayerPos = null;

    @Unique
    private long stellarCore_playerPosLastTick = 0;

    /**
     * 在 RedstoneWireBlock.scheduledTick 方法头部注入。
     * 
     * scheduledTick 是红石线在接收到方块更新调度后执行的方法，
     * 负责重新计算信号强度并更新相邻方块。
     * 这是红石传播的核心入口。
     * 
     * 我们在此注入点检查：
     * 1. 该红石线所在区块是否处于 FROZEN 或 POTENTIAL 状态
     * 2. 如果该区块已被惰性化，检查是否已有快照
     * 3. 如果有快照，跳过此次 tick（由快照系统在解冻时恢复）
     * 4. 如果没有快照且距离超过阈值，创建快照并冻结
     *
     * @param state  红石线的方块状态
     * @param world  世界对象
     * @param pos    红石线位置
     * @param random 随机数生成器
     * @param ci     回调信息
     */
    @Inject(
        method = "scheduledTick",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onScheduledTickHead(
            BlockState state,
            ServerWorld world,
            BlockPos pos,
            Random random,
            CallbackInfo ci) {

        if (world == null || pos == null) return;

        stellarCore_updateCounter++;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        StellarConfig config = instance.getConfig();
        if (config == null) return;

        // 降频检查：不是每次红石更新都需要查惰性化状态
        if (stellarCore_updateCounter % LAZY_CHECK_INTERVAL != 0) {
            return; // 放行，继续 Vanilla 正常处理
        }

        // 获取玩家位置
        Vec3d playerPos = getCachedPlayerPosition(world);
        if (playerPos == null) return; // 没有玩家，放行

        // 检查区块状态
        ChunkPos chunkPos = new ChunkPos(pos);
        ChunkStateManager stateManager = instance.getChunkStateManager();
        LazyRedstone lazyRedstone = instance.getLazyRedstone();

        // 优先级1：区块状态机判定
        if (stateManager != null) {
            ChunkState chunkState = stateManager.getState(chunkPos);

            if (chunkState == ChunkState.FROZEN || chunkState == ChunkState.POTENTIAL) {
                // 区块已冻结，取消红石 tick
                ci.cancel();
                return;
            }
        }

        // 优先级2：红石惰性化引擎判定
        if (lazyRedstone != null) {
            if (lazyRedstone.shouldFreeze(world, pos, playerPos)) {
                ci.cancel();
                return;
            }

            // 检查该区块是否整体被冻结
            if (lazyRedstone.isChunkFrozen(chunkPos)) {
                ci.cancel();
                return;
            }
        }
    }

    /**
     * 在 RedstoneWireBlock.update 方法头部注入。
     * 
     * update 是红石线在邻近方块发生变化时被调用的方法，
     * 负责重新计算自身的信号强度并安排 scheduledTick。
     * 
     * 对于已冻结区块中的红石线，跳过更新以减少连锁反应。
     *
     * @param world 世界对象
     * @param pos   红石线位置
     * @param state 红石线状态
     * @param ci    回调信息
     */
    @Inject(
        method = "update",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUpdateHead(World world, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (world == null || pos == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkPos chunkPos = new ChunkPos(pos);

        // 检查区块状态管理器
        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager != null) {
            ChunkState chunkState = stateManager.getState(chunkPos);
            if (chunkState == ChunkState.FROZEN || chunkState == ChunkState.POTENTIAL) {
                ci.cancel();
                return;
            }
        }

        // 检查红石惰性化引擎
        LazyRedstone lazyRedstone = instance.getLazyRedstone();
        if (lazyRedstone != null && lazyRedstone.isChunkFrozen(chunkPos)) {
            ci.cancel();
            return;
        }
    }

    /**
     * 在 RedstoneWireBlock.neighborUpdate 方法头部注入。
     * 
     * neighborUpdate 是红石线在收到邻居方块状态变化通知时调用的方法。
     * 对于冻结区块，跳过此更新以避免不必要的连锁计算。
     *
     * @param state         红石线状态
     * @param world         世界对象
     * @param pos           红石线位置
     * @param sourceBlock   触发更新的方块
     * @param sourcePos     触发更新的方块位置
     * @param notify        是否通知
     * @param ci            回调信息
     */
    @Inject(
        method = "neighborUpdate",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onNeighborUpdateHead(
            BlockState state,
            World world,
            BlockPos pos,
            Block sourceBlock,
            BlockPos sourcePos,
            boolean notify,
            CallbackInfo ci) {

        if (world == null || pos == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkPos chunkPos = new ChunkPos(pos);

        // 检查区块状态
        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager != null) {
            ChunkState chunkState = stateManager.getState(chunkPos);
            if (chunkState == ChunkState.FROZEN || chunkState == ChunkState.POTENTIAL) {
                ci.cancel();
                return;
            }
        }

        // 检查红石惰性化
        LazyRedstone lazyRedstone = instance.getLazyRedstone();
        if (lazyRedstone != null && lazyRedstone.isChunkFrozen(chunkPos)) {
            ci.cancel();
            return;
        }
    }

    /**
     * 在红石线被放置时，通知红石惰性化引擎有新组件加入。
     *
     * @param world 世界对象
     * @param pos   放置位置
     * @param state 方块状态
     * @param ci    回调信息
     */
    @Inject(
        method = "onBlockAdded",
        at = @At("TAIL")
    )
    private void onBlockAddedTail(BlockState state, World world, BlockPos pos,
                                   BlockState oldState, boolean notify, CallbackInfo ci) {
        if (world == null || pos == null) return;

        // 仅在服务端处理
        if (!(world instanceof ServerWorld)) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        LazyRedstone lazyRedstone = instance.getLazyRedstone();
        if (lazyRedstone == null) return;

        // 如果该位置所在区块已有快照，红石线的添加可能导致快照失效
        ChunkPos chunkPos = new ChunkPos(pos);
        if (lazyRedstone.isChunkFrozen(chunkPos)) {
            // 区块已被冻结但玩家放置了红石线，需要解冻
            ServerWorld serverWorld = (ServerWorld) world;
            Vec3d playerPos = getCachedPlayerPosition(serverWorld);
            if (playerPos != null) {
                lazyRedstone.thawChunk(serverWorld, chunkPos, playerPos);
            }
        }
    }

    /**
     * 获取缓存的玩家位置。
     * 与 MobEntityMixin 中的实现逻辑一致，复用相同的缓存策略。
     *
     * @param world 世界对象
     * @return 玩家位置，如果没有玩家则返回 null
     */
    @Unique
    private Vec3d getCachedPlayerPosition(ServerWorld world) {
        long currentTick = world.getTime();

        // 缓存有效期：10 tick
        if (stellarCore_cachedPlayerPos != null
            && (currentTick - stellarCore_playerPosLastTick) < 10) {
            return stellarCore_cachedPlayerPos;
        }

        // 刷新缓存
        var players = world.getPlayers();
        if (players.isEmpty()) {
            stellarCore_cachedPlayerPos = null;
            stellarCore_playerPosLastTick = currentTick;
            return null;
        }

        // 使用第一个玩家作为参考
        stellarCore_cachedPlayerPos = players.get(0).getPos();
        stellarCore_playerPosLastTick = currentTick;
        return stellarCore_cachedPlayerPos;
    }
}