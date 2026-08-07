package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;
import com.stellar.core.logic.LazyEntityAI;
import com.stellar.core.logic.LazyEntityAI.EntitySnapshot;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {

    @Shadow
    @Final
    protected GoalSelector goalSelector;

    @Shadow
    @Final
    protected GoalSelector targetSelector;

    @Shadow
    public abstract Brain<?> getBrain();

    @Shadow
    protected abstract void mobTick();

    /**
     * 强制构造函数（Mixin 要求，不会实际调用）
     */
    protected MobEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    // ========== 内部状态标记 ==========

    @Unique
    private boolean stellarCore_isFrozen = false;

    @Unique
    private int stellarCore_frozenTickCounter = 0;

    @Unique
    private static final int REVIVE_CHECK_INTERVAL = 40; // 每 40 tick（2秒）检查一次是否解冻

    @Unique
    private Vec3d stellarCore_cachedPlayerPos = null;

    @Unique
    private long stellarCore_playerPosLastUpdated = 0;

    /**
     * 在 MobEntity.tickNewAi 方法头部注入。
     * 
     * tickNewAi 是 MobEntity 每 tick 调用一次的核心 AI 方法，
     * 负责更新生物的目标选择器（goalSelector）和目标选择器（targetSelector），
     * 以及处理生物的大脑（Brain）系统（1.20 的村民等使用此系统）。
     * 
     * 这是实体 AI 中开销最大的部分，尤其是寻路算法。
     * 我们在此注入点检查实体是否应进入势能态，
     * 如果是，则跳过本次 AI tick。
     *
     * @param ci 回调信息
     */
    @Inject(
        method = "tickNewAi",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onTickNewAiHead(CallbackInfo ci) {
        // 获取星核引擎实例
        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        StellarConfig config = instance.getConfig();
        if (config == null) return;

        LazyEntityAI lazyAI = instance.getLazyEntityAI();
        if (lazyAI == null) return;

        MobEntity self = (MobEntity) (Object) this;

        // 已死亡或已移除的实体不需要 AI
        if (!self.isAlive() || self.isRemoved()) {
            return;
        }

        // 获取最近玩家位置（带缓存，避免每 tick 查找）
        Vec3d playerPos = getCachedPlayerPosition(self);
        if (playerPos == null) {
            return; // 没有玩家，保持 Vanilla 默认行为
        }

        // 调用惰性化判定
        if (lazyAI.shouldFreeze(self, playerPos)) {
            // 实体进入或保持势能态，取消本次 AI tick
            stellarCore_isFrozen = true;
            stellarCore_frozenTickCounter++;

            // 定期检查是否应该解冻
            if (stellarCore_frozenTickCounter % REVIVE_CHECK_INTERVAL == 0) {
                // 如果玩家已经足够近，解冻
                double distance = self.getPos().distanceTo(playerPos);
                if (distance < config.entityFreezeRadius) {
                    lazyAI.thawEntityAt(self, playerPos);
                    stellarCore_isFrozen = false;
                    stellarCore_frozenTickCounter = 0;
                    return; // 解冻后放行本次 AI tick
                }
            }

            // 取消 AI tick
            ci.cancel();
            return;
        }

        // 实体未冻结
        if (stellarCore_isFrozen) {
            // 刚从势能态恢复
            stellarCore_isFrozen = false;
            stellarCore_frozenTickCounter = 0;
        }
    }

    /**
     * 在 MobEntity.tickMovement 方法头部注入。
     * 
     * tickMovement 负责实体的物理移动计算（速度、重力、碰撞检测等）。
     * 对于进入势能态的实体，我们同样跳过移动 tick，
     * 使用"时间跳跃"算法在恢复时计算预测位置。
     *
     * @param ci 回调信息
     */
    @Inject(
        method = "tickMovement",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onTickMovementHead(CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;

        // 如果实体已被标记为冻结，跳过移动 tick
        if (stellarCore_isFrozen) {
            ci.cancel();
        }
    }

    /**
     * 在 MobEntity.mobTick 方法头部注入。
     * 
     * mobTick 是生物特有的周期性 tick（不同于 tickNewAi），
     * 处理生物特有的行为（如僵尸检查日光、生物在特定时间发出声音等）。
     * 对于势能态实体，同样跳过。
     *
     * @param ci 回调信息
     */
    @Inject(
        method = "mobTick",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onMobTickHead(CallbackInfo ci) {
        if (stellarCore_isFrozen) {
            ci.cancel();
        }
    }

    /**
     * 在实体被移除（死亡、卸载、传送到其他维度）时清理惰性化状态。
     *
     * @param reason 移除原因
     * @param ci     回调信息
     */
    @Inject(
        method = "remove",
        at = @At("HEAD")
    )
    private void onRemoveHead(Entity.RemovalReason reason, CallbackInfo ci) {
        MobEntity self = (MobEntity) (Object) this;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        LazyEntityAI lazyAI = instance.getLazyEntityAI();
        if (lazyAI != null) {
            lazyAI.onEntityRemoved(self);
        }

        stellarCore_isFrozen = false;
        stellarCore_frozenTickCounter = 0;
    }

    /**
     * 获取缓存的玩家位置。
     * 
     * 在服务端，每 tick 查找最近玩家开销较大（需要遍历玩家列表并计算距离）。
     * 我们缓存玩家位置并每 10 tick 刷新一次，
     * 在大多数情况下玩家移动速度不会在 10 tick 内产生显著的 AI 判定差异。
     *
     * @param entity 当前实体
     * @return 缓存的玩家位置
     */
    @Unique
    private Vec3d getCachedPlayerPosition(MobEntity entity) {
        long currentTick = entity.getWorld().getTime();

        // 缓存有效期：10 tick
        if (stellarCore_cachedPlayerPos != null
            && (currentTick - stellarCore_playerPosLastUpdated) < 10) {
            return stellarCore_cachedPlayerPos;
        }

        // 刷新缓存
        World world = entity.getWorld();
        if (world instanceof ServerWorld serverWorld) {
            PlayerEntity nearestPlayer = serverWorld.getClosestPlayer(
                entity.getX(), entity.getY(), entity.getZ(),
                512.0, // 最大搜索范围
                p -> true
            );

            if (nearestPlayer != null) {
                stellarCore_cachedPlayerPos = nearestPlayer.getPos();
                stellarCore_playerPosLastUpdated = currentTick;
                return stellarCore_cachedPlayerPos;
            }
        }

        // 如果找不到玩家，尝试使用实体自身位置作为后备
        // 这样 shouldFreeze 会因为没有玩家引用而返回 false
        stellarCore_cachedPlayerPos = null;
        stellarCore_playerPosLastUpdated = currentTick;
        return null;
    }

    /**
     * 检查实体是否被星核引擎冻结。
     * 供其他 Mixin 类或调试命令查询。
     *
     * @return true 表示实体处于势能态
     */
    @Unique
    public boolean stellarCore_isFrozen() {
        return stellarCore_isFrozen;
    }

    /**
     * 获取实体在势能态中跳过的 tick 数。
     *
     * @return 跳过的 tick 数
     */
    @Unique
    public int stellarCore_getFrozenTickCount() {
        return stellarCore_frozenTickCounter;
    }
}