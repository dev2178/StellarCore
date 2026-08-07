package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;
import com.stellar.core.render.DynamicLOD;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    /**
     * 缓存最近一次获取的配置引用，避免每次注入都调用 getInstance()
     */
    @Unique
    private StellarConfig stellarCore_cachedConfig = null;

    @Unique
    private long stellarCore_configLastChecked = 0;

    @Unique
    private static final long CONFIG_CACHE_DURATION_MS = 5000; // 5秒缓存

    /**
     * 在 shouldRender 方法头部注入。
     * 
     * shouldRender 是 Vanilla 判断某个实体是否应该被渲染的方法。
     * 我们在 Vanilla 的视锥剔除和距离检查之前，先进行星核引擎的优化判定：
     * 
     * 1. 实体距离剔除：距离玩家超过配置阈值的实体不渲染
     * 2. 动态LOD剔除：LOD级别为 MINIMAL 或 GROUND 时不渲染实体
     * 3. 视锥剔除增强：利用八叉树预计算结果快速跳过
     *
     * @param entity   待渲染的实体
     * @param frustum  视锥体对象
     * @param x        相机X坐标
     * @param y        相机Y坐标
     * @param z        相机Z坐标
     * @param cir      回调信息（可取消Vanilla的后续判断）
     */
    @Inject(
        method = "shouldRender",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onShouldRender(
            T entity,
            Frustum frustum,
            double x,
            double y,
            double z,
            CallbackInfoReturnable<Boolean> cir) {

        if (entity == null) return;

        // 获取配置（带缓存，避免高频调用 getInstance）
        StellarConfig config = getCachedConfig();
        if (config == null) return;

        // 获取星核引擎实例
        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        // ========== 第1层：实体距离剔除 ==========
        // 计算实体到相机的距离
        double dx = entity.getX() - x;
        double dy = entity.getY() - y;
        double dz = entity.getZ() - z;
        double distanceSq = dx * dx + dy * dy + dz * dz;

        double freezeRadius = config.entityFreezeRadius;
        double freezeRadiusSq = freezeRadius * freezeRadius;

        if (distanceSq > freezeRadiusSq) {
            // 实体超出冻结半径，检查是否可以安全剔除
            // 玩家实体始终渲染
            if (entity instanceof PlayerEntity) {
                return; // 不取消，继续 Vanilla 的正常判断
            }

            // 命名实体豁免检查
            if (config.entityNamedExempt && entity.hasCustomName()) {
                return;
            }

            // 标记为剔除并取消 Vanilla 的后续判断
            cir.setReturnValue(false);
            instance.addCulledChunks(1); // 复用计数器
            return;
        }

        // ========== 第2层：动态LOD剔除 ==========
        // 检查该实体所在区块的LOD级别
        DynamicLOD lod = instance.getDynamicLOD();
        if (lod != null) {
            // 获取实体所在的区块坐标
            int chunkX = entity.getChunkPos().x;
            int chunkZ = entity.getChunkPos().z;
            double chunkCenterX = chunkX * 16 + 8.0;
            double chunkCenterZ = chunkZ * 16 + 8.0;

            Vec3d cameraPos = new Vec3d(x, y, z);
            int lodLevel = lod.getLODLevelFast(chunkCenterX, chunkCenterZ, cameraPos);

            // 根据LOD级别决定是否渲染实体
            if (!DynamicLOD.shouldRenderEntities(lodLevel)) {
                cir.setReturnValue(false);
                return;
            }
        }

        // ========== 第3层：实体AI惰性化联动 ==========
        // 如果实体已被惰性化引擎冻结，降低其渲染优先级
        if (instance.getLazyEntityAI() != null
            && instance.getLazyEntityAI().isFrozen(entity)) {
            // 惰性化实体的渲染距离减半
            double halfRadius = freezeRadius / 2.0;
            if (distanceSq > halfRadius * halfRadius) {
                cir.setReturnValue(false);
                return;
            }
        }

        // 通过所有检查，继续 Vanilla 的正常渲染判断
    }

    /**
     * 获取缓存的配置对象。
     * 每5秒刷新一次，避免每次注入都通过 getInstance() 链式获取。
     */
    @Unique
    private StellarConfig getCachedConfig() {
        long now = System.currentTimeMillis();
        if (stellarCore_cachedConfig == null
            || (now - stellarCore_configLastChecked) > CONFIG_CACHE_DURATION_MS) {
            StellarCore instance = StellarCore.getInstance();
            if (instance != null) {
                stellarCore_cachedConfig = instance.getConfig();
                stellarCore_configLastChecked = now;
            }
        }
        return stellarCore_cachedConfig;
    }
}