package com.stellar.core.mixin;

import com.stellar.core.StellarCore;
import com.stellar.core.config.StellarConfig;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    @Shadow
    @Final
    private Map<ParticleTextureSheet, Queue<Particle>> particles;

    @Shadow
    public abstract void addParticle(Particle particle);

    @Shadow
    protected abstract void tickParticle(Particle particle);

    @Unique
    private int stellarCore_tickCounter = 0;

    @Unique
    private static final int CLEANUP_INTERVAL_TICKS = 10;

    @Unique
    private long stellarCore_totalParticlesRemoved = 0;

    @Unique
    private long stellarCore_totalParticlesSkipped = 0;

    /**
     * 在 ParticleManager.tick 方法末尾注入。
     * 
     * tick 方法是每帧调用一次的核心方法，负责更新所有活跃粒子的状态。
     * Vanilla 在此方法中遍历所有粒子并调用 tickParticle 进行更新。
     * 
     * 我们的优化策略：
     * 1. 在 Vanilla 完成粒子更新后，统计当前粒子总数
     * 2. 如果超过配置的上限，从旧粒子开始移除多余的粒子
     * 3. 爆炸粒子可配置豁免（单独上限）
     *
     * @param ci 回调信息
     */
    @Inject(
        method = "tick",
        at = @At("TAIL")
    )
    private void onTickTail(CallbackInfo ci) {
        stellarCore_tickCounter++;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        StellarConfig config = instance.getConfig();
        if (config == null) return;

        // 每隔 CLEANUP_INTERVAL_TICKS 帧执行一次清理
        // 不需要每帧都检查，减少开销
        if (stellarCore_tickCounter % CLEANUP_INTERVAL_TICKS != 0) {
            return;
        }

        int globalMax = config.maxParticles;
        int explosionMax = config.particleExplosionMax;
        boolean explosionExempt = config.particleExplosionExempt;

        // 统计当前粒子总数
        int totalParticles = 0;
        int explosionParticles = 0;

        List<Particle> allParticles = new ArrayList<>();
        List<Particle> explosionParticleList = new ArrayList<>();

        // 遍历所有粒子队列，收集粒子
        for (Queue<Particle> queue : particles.values()) {
            for (Particle particle : queue) {
                totalParticles++;
                allParticles.add(particle);

                // 识别爆炸粒子
                if (isExplosionParticle(particle)) {
                    explosionParticles++;
                    explosionParticleList.add(particle);
                }
            }
        }

        // ========== 第1步：爆炸粒子单独限制 ==========
        if (explosionExempt && explosionParticles > explosionMax) {
            int toRemove = explosionParticles - explosionMax;
            int removed = 0;

            // 移除最旧的爆炸粒子（队列头部 = 最早添加的）
            for (Queue<Particle> queue : particles.values()) {
                List<Particle> toRemoveList = new ArrayList<>();
                for (Particle particle : queue) {
                    if (removed >= toRemove) break;
                    if (isExplosionParticle(particle)) {
                        toRemoveList.add(particle);
                        removed++;
                    }
                }
                queue.removeAll(toRemoveList);
                stellarCore_totalParticlesRemoved += toRemoveList.size();
            }
        }

        // ========== 第2步：全局粒子上限 ==========
        int currentTotal = countAllParticles();
        if (currentTotal > globalMax) {
            int toRemove = currentTotal - globalMax;
            int removed = 0;

            // 按队列遍历，移除最旧的粒子
            // 策略：从每个队列的头部开始移除（FIFO，最旧的先移除）
            for (Queue<Particle> queue : particles.values()) {
                if (removed >= toRemove) break;

                while (!queue.isEmpty() && removed < toRemove) {
                    Particle oldest = queue.poll(); // 移除队列头部（最旧）
                    if (oldest != null) {
                        oldest.markDead(); // 标记为死亡，确保被清理
                        removed++;
                        stellarCore_totalParticlesRemoved++;
                    }
                }
            }
        }

        // 更新全局统计
        instance.addCulledChunks(stellarCore_totalParticlesRemoved);
    }

    /**
     * 在 ParticleManager.addParticle 方法头部注入。
     * 
     * 在 Vanilla 添加新粒子之前检查全局粒子数量。
     * 如果粒子数已达上限且不满足豁免条件，跳过添加。
     * 这是"预防性"策略：在粒子产生阶段就限制，而不是等粒子多了再清理。
     *
     * @param particle 待添加的粒子
     * @param ci       回调信息
     */
    @Inject(
        method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onAddParticleHead(Particle particle, CallbackInfo ci) {
        if (particle == null) return;

        StellarCore instance = StellarCore.getInstance();
        if (instance == null) return;

        StellarConfig config = instance.getConfig();
        if (config == null) return;

        int globalMax = config.maxParticles;
        boolean explosionExempt = config.particleExplosionExempt;
        int explosionMax = config.particleExplosionMax;

        // 统计当前粒子数
        int currentTotal = countAllParticles();

        // 如果未达到上限，放行
        if (currentTotal < globalMax) {
            return;
        }

        // 已达上限，检查豁免条件
        if (explosionExempt && isExplosionParticle(particle)) {
            // 爆炸粒子豁免：检查爆炸粒子单独上限
            int currentExplosion = countExplosionParticles();
            if (currentExplosion < explosionMax) {
                return; // 放行
            }
        }

        // 粒子已达上限且不满足豁免条件，取消添加
        ci.cancel();
        stellarCore_totalParticlesSkipped++;
    }

    /**
     * 统计所有队列中的粒子总数。
     * 遍历 particles Map 的所有队列并累加大小。
     *
     * @return 当前活跃粒子总数
     */
    @Unique
    private int countAllParticles() {
        int count = 0;
        for (Queue<Particle> queue : particles.values()) {
            if (queue != null) {
                count += queue.size();
            }
        }
        return count;
    }

    /**
     * 统计爆炸粒子的数量。
     * 遍历所有队列，检查每个粒子是否为爆炸粒子。
     *
     * @return 当前爆炸粒子数量
     */
    @Unique
    private int countExplosionParticles() {
        int count = 0;
        for (Queue<Particle> queue : particles.values()) {
            if (queue != null) {
                for (Particle particle : queue) {
                    if (isExplosionParticle(particle)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * 判断一个粒子是否为爆炸粒子。
     * 
     * 爆炸粒子的特征：
     * - 类名包含 "Explosion"（爆炸）
     * - 类名包含 "Emitter" 且与爆炸相关
     * - 粒子速度较大（爆炸粒子通常有较大的初始速度）
     *
     * 由于无法直接 import 客户端类（在 Mixin 中 import 可能导致类加载问题），
     * 使用类名字符串判断 + 速度特征作为后备。
     *
     * @param particle 待检查的粒子
     * @return true 表示该粒子是爆炸粒子
     */
    @Unique
    private boolean isExplosionParticle(Particle particle) {
        if (particle == null) return false;

        String className = particle.getClass().getName().toLowerCase();

        // 类名特征匹配
        if (className.contains("explosion")) {
            return true;
        }

        // 爆炸发射器粒子
        if (className.contains("explosionemitter")) {
            return true;
        }

        // 速度特征：爆炸粒子通常速度 > 0.5
        // 使用 getVelocity 方法的引用进行判断
        Vec3d velocity = particle.getVelocity();
        if (velocity != null) {
            double speed = velocity.length();
            if (speed > 1.5 && particle.getMaxAge() <= 40) {
                // 高速度 + 短生命周期 ≈ 爆炸粒子
                return true;
            }
        }

        return false;
    }

    /**
     * 获取被移除的粒子总数（用于统计命令）。
     */
    @Unique
    public long getTotalParticlesRemoved() {
        return stellarCore_totalParticlesRemoved;
    }

    /**
     * 获取被跳过添加的粒子总数（用于统计命令）。
     */
    @Unique
    public long getTotalParticlesSkipped() {
        return stellarCore_totalParticlesSkipped;
    }
}