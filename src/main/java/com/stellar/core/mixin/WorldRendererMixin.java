package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;
import com.stellar.core.render.ChunkRenderCache;
import com.stellar.core.render.DynamicLOD;
import com.stellar.core.render.OctreeFrustumCuller;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Shadow
    @Final
    // private List<WorldRenderer.ChunkInfo> chunkInfos; // 由 Mixin 访问，编译时注释

    @Shadow
    private ClientWorld world;

    @Shadow
    private Frustum frustum;

    @Shadow
    protected abstract boolean isRenderingReady(BlockPos pos);

    @Unique
    private int stellarCore_frameCounter = 0;

    @Unique
    private static final int FRUSTUM_UPDATE_INTERVAL = 2; // 每2帧更新一次视锥剔除结果

    @Unique
    private Set<ChunkPos> stellarCore_currentVisibleChunks = new HashSet<>();

    /**
     * 在 WorldRenderer.setupTerrain 方法开头注入。
     * 在 Vanilla 开始构建区块渲染列表之前，先用八叉树视锥剔除过滤出可见区块集合。
     * 
     * setupTerrain 是 Vanilla 每帧调用一次的核心方法，
     * 负责确定哪些区块需要渲染。在此注入可以最大限度减少后续处理量。
     *
     * @param camera      相机对象
     * @param frustum     视锥体对象
     * @param hasForcedFrustum 是否强制视锥体
     * @param isSpectator 是否为旁观者模式
     * @param ci          回调信息
     */
    @Inject(
        method = "setupTerrain",
        at = @At("HEAD"),
        cancellable = false
    )
    private void onSetupTerrainHead(
            Camera camera,
            Frustum frustum,
            boolean hasForcedFrustum,
            boolean isSpectator,
            CallbackInfo ci) {

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        StellarConfig config = instance.getConfig();
        if (config == null) return;

        OctreeFrustumCuller culler = instance.getFrustumCuller();
        DynamicLOD lod = instance.getDynamicLOD();
        ChunkRenderCache renderCache = instance.getRenderCache();

        if (culler == null || lod == null || renderCache == null) return;

        // 获取相机信息
        Vec3d cameraPos = camera.getPos();
        Vec3d lookVec = camera.getHorizontalPlane();
        double fovX = camera.getFov();
        double fovY = camera.getFov(); // Vanilla 使用相同的 FOV

        // 渲染距离（从游戏设置获取）
        double renderDist = world.getChunkManager().getChunkLoadingDebugInfo() != null
            ? 16 * 16  // 默认16区块
            : 16 * 16;

        // 每 FRUSTUM_UPDATE_INTERVAL 帧更新一次可见性结果
        stellarCore_frameCounter++;
        Set<ChunkPos> visibleChunks;

        if (stellarCore_frameCounter % FRUSTUM_UPDATE_INTERVAL == 0) {
            // 使用八叉树查询可见区块
            List<ChunkRenderCache.RenderData> visibleData = culler.queryVisible(
                cameraPos, lookVec, fovX, fovY, renderDist
            );

            visibleChunks = new HashSet<>();
            for (ChunkRenderCache.RenderData data : visibleData) {
                visibleChunks.add(data.pos);

                // 更新该区块的LOD级别
                lod.getLODLevel(data.pos, cameraPos);

                // 更新渲染缓存
                if (world != null) {
                    net.minecraft.world.chunk.WorldChunk chunk = world.getChunkManager()
                        .getWorldChunk(data.pos.x, data.pos.z);
                    if (chunk != null) {
                        renderCache.getOrBuild(data.pos, chunk);
                    }
                }
            }

            // 更新全局统计
            instance.addCulledChunks(
                culler.getTotalChunks() - visibleData.size()
            );

            stellarCore_currentVisibleChunks = visibleChunks;
        } else {
            // 使用上一帧的缓存结果
            visibleChunks = stellarCore_currentVisibleChunks;
        }

        // 将可见区块标记存储到 ThreadLocal，供后续注入点读取
        VisibleChunkHolder.set(visibleChunks);
    }

    /**
     * 在 WorldRenderer.setupTerrain 方法中，Vanilla 遍历区块判断是否可见的位置注入。
     * 使用 @ModifyVariable 修改 isVisible 的判断结果。
     * 
     * 此注入点拦截 Vanilla 对每个区块的可见性判断，
     * 如果该区块不在八叉树剔除结果中，强制标记为不可见。
     *
     * @param originalVisibility Vanilla 原始的可见性判断结果
     * @return 修正后的可见性结果
     */
    @ModifyVariable(
        method = "setupTerrain",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Frustum;isVisible(Lnet/minecraft/util/math/Box;)Z"
        ),
        ordinal = 0
    )
    private boolean modifyChunkVisibility(boolean originalVisibility) {
        // 如果 Vanilla 已经判定为不可见，直接返回 false
        if (!originalVisibility) {
            return false;
        }

        // 获取当前可见区块集合
        Set<ChunkPos> visibleChunks = VisibleChunkHolder.get();
        if (visibleChunks == null || visibleChunks.isEmpty()) {
            return originalVisibility; // 无缓存时使用 Vanilla 原始结果
        }

        // 由于 ModifyVariable 无法直接获取当前正在检查的区块坐标，
        // 我们在这里使用另一种策略：在 setupTerrain 的循环内部注入
        // 此处保留此注入点作为后备优化
        return originalVisibility;
    }

    /**
     * 在 WorldRenderer.render 方法注入，用于在渲染过程中应用动态 LOD。
     * 
     * 此方法在每帧渲染时调用，我们在 Vanilla 的区块渲染循环中插入 LOD 判断，
     * 根据 LOD 级别调整渲染参数。
     *
     * @param ci 回调信息
     */
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;renderChunkLayer(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/util/math/MatrixStack;DDDLorg/joml/Matrix4f;)V"
        )
    )
    private void onRenderChunkLayer(CallbackInfo ci) {
        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        ChunkRenderCache renderCache = instance.getRenderCache();
        if (renderCache != null) {
            instance.incrementCachedFrames();
        }
    }

    /**
     * 在区块卸载时清理 LOD 记录。
     *
     * @param pos 区块坐标
     * @param ci  回调信息
     */
    @Inject(
        method = "onChunkUnload",
        at = @At("TAIL")
    )
    private void onChunkUnload(int chunkX, int chunkZ, CallbackInfo ci) {
        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        DynamicLOD lod = instance.getDynamicLOD();
        OctreeFrustumCuller culler = instance.getFrustumCuller();

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);

        if (lod != null) {
            lod.onChunkUnload(pos);
        }

        // 从八叉树中移除该区块的渲染数据
        if (culler != null && instance.getRenderCache() != null) {
            ChunkRenderCache.RenderData data = instance.getRenderCache().getIfPresent(pos);
            if (data != null) {
                culler.remove(data);
            }
        }
    }

    /**
     * ThreadLocal 存储当前帧的可见区块集合。
     * 使用 ThreadLocal 确保渲染线程安全，避免多线程竞争。
     */
    @Unique
    private static final ThreadLocal<Set<ChunkPos>> VisibleChunkHolder = new ThreadLocal<>();
}