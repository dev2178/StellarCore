package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;
import com.stellar.core.logic.ChunkStateManager;
import com.stellar.core.logic.ChunkStateManager.ChunkState;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {

    @Shadow
    public abstract List<net.minecraft.server.network.ServerPlayerEntity> getPlayers();

    @Unique
    private int stellarCore_tickCounter = 0;

    @Unique
    private static final int STATE_UPDATE_INTERVAL = 20; // 每秒更新一次区块状态

    @Unique
    private Vec3d stellarCore_cachedPlayerPos = null;

    /**
     * 在 ServerWorld.tick 方法头部注入。
     * 
     * tick 方法是服务器世界每游戏刻调用一次的核心方法，
     * 负责驱动所有区块、实体、方块实体的更新。
     * 
     * 我们在此注入点执行区块状态机的周期性更新：
     * 1. 获取当前世界中的玩家位置
     * 2. 调用 ChunkStateManager.updateAllStates() 更新所有区块的状态
     * 3. 清理过期的实体AI快照和红石快照
     *
     * @param shouldKeepTicking 世界是否应继续 tick
     * @param ci                回调信息
     */
    @Inject(
        method = "tick",
        at = @At("HEAD")
    )
    private void onTickHead(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        stellarCore_tickCounter++;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager == null) return;

        ServerWorld self = (ServerWorld) (Object) this;

        // 获取玩家位置（用于区块状态更新）
        Vec3d playerPos = getPrimaryPlayerPosition();
        if (playerPos == null) return;

        // 记录玩家位置用于后续注入点
        stellarCore_cachedPlayerPos = playerPos;

        // 每 STATE_UPDATE_INTERVAL tick 更新一次区块状态
        if (stellarCore_tickCounter % STATE_UPDATE_INTERVAL == 0) {
            stateManager.updateAllStates(self, playerPos);
        }

        // 当玩家移动时进行轻量级状态更新
        if (stellarCore_tickCounter % 5 == 0) {
            // 每5 tick检查一次玩家移动（比 STATE_UPDATE_INTERVAL 更频繁）
            stateManager.onPlayerMove(playerPos, stellarCore_cachedPlayerPos);
        }

        // 定期清理过期快照（每 100 tick = 5秒）
        if (stellarCore_tickCounter % 100 == 0) {
            if (instance.getLazyEntityAI() != null) {
                instance.getLazyEntityAI().cleanExpiredSnapshots();
            }
            if (instance.getLazyRedstone() != null) {
                instance.getLazyRedstone().cleanExpiredSnapshots();
            }
        }
    }

    /**
     * 在 ServerWorld.tickChunk 方法头部注入。
     * 
     * tickChunk 是 Vanilla 对单个区块执行 tick 的方法，
     * 包括随机刻、方块实体 tick、流体扩散等。
     * 
     * 我们在此注入点检查该区块的状态：
     * - ACTIVE: 放行，Vanilla 正常执行全部 tick
     * - IDLE: 放行但后续会通过其他 Mixin 限制 tick 范围
     * - FROZEN: 取消 tick，该区块完全冻结
     * - POTENTIAL: 取消 tick，该区块不应被加载
     *
     * @param chunk 待 tick 的区块
     * @param ci    回调信息
     */
    @Inject(
        method = "tickChunk",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onTickChunkHead(WorldChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (chunk == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager == null) return;

        ChunkPos chunkPos = chunk.getPos();
        ChunkState state = stateManager.getState(chunkPos);

        switch (state) {
            case ACTIVE:
                // 全模拟，放行
                break;

            case IDLE:
                // IDLE 状态：允许 tickChunk 执行，但跳过随机刻
                // 随机刻的跳过通过另一个注入点或直接在 tickChunk 逻辑中处理
                // 此处放行，由后续的随机刻注入点控制
                break;

            case FROZEN:
                // 完全冻结，取消 tick
                ci.cancel();
                if (StellarCore.getInstance().getConfig().debugVerboseLogging
                    && stellarCore_tickCounter % 100 == 0) {
                    // 每 5 秒只记录一次，避免日志刷屏
                }
                break;

            case POTENTIAL:
                // 势能态，取消 tick（该区块理论上不应被加载）
                ci.cancel();
                break;

            default:
                break;
        }
    }

    /**
     * 在区块加载时更新区块状态。
     * 
     * Vanilla 在加载新区块时调用此方法。
     * 我们在此注入点通知 ChunkStateManager 有新区块加入，
     * 以便状态管理器正确追踪和分配初始状态。
     *
     * @param chunk 新加载的区块
     * @param ci    回调信息
     */
    @Inject(
        method = "onChunkLoad",
        at = @At("TAIL")
    )
    private void onChunkLoadTail(WorldChunk chunk, CallbackInfo ci) {
        if (chunk == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager == null) return;

        ServerWorld self = (ServerWorld) (Object) this;
        stateManager.onChunkLoad(chunk.getPos(), self);
    }

    /**
     * 在区块卸载时清理区块状态。
     *
     * @param chunk 即将卸载的区块
     * @param ci    回调信息
     */
    @Inject(
        method = "onChunkUnload",
        at = @At("HEAD")
    )
    private void onChunkUnloadHead(WorldChunk chunk, CallbackInfo ci) {
        if (chunk == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        // 通知区块状态管理器
        ChunkStateManager stateManager = instance.getChunkStateManager();
        if (stateManager != null) {
            stateManager.onChunkUnload(chunk.getPos());
        }

        // 通知红石惰性化引擎
        if (instance.getLazyRedstone() != null) {
            instance.getLazyRedstone().onChunkUnload(chunk.getPos());
        }
    }

    /**
     * 获取主玩家位置（第一个玩家的位置）。
     * 如果世界中有多个玩家，使用最近添加的玩家位置。
     *
     * @return 主玩家位置，如果没有玩家则返回 null
     */
    @Unique
    private Vec3d getPrimaryPlayerPosition() {
        List<net.minecraft.server.network.ServerPlayerEntity> players = getPlayers();
        if (players == null || players.isEmpty()) return null;

        // 使用第一个玩家作为参考
        net.minecraft.server.network.ServerPlayerEntity primaryPlayer = players.get(0);
        return primaryPlayer.getPos();
    }
}