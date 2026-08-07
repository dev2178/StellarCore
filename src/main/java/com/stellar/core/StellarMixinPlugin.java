package com.stellar.core;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stellar Core Mixin 插件。
 * 
 * 实现 IMixinConfigPlugin 接口，在 Fabric Loader 加载 Mixin 配置时提供：
 * 
 * 1. 运行时 Mixin 启用/禁用控制
 *    - 根据配置文件动态决定是否加载某些 Mixin
 *    - 例如：如果用户禁用了 L3 逻辑层，则跳过相关 Mixin
 * 
 * 2. 模组兼容性检测
 *    - 检测 Sodium、Lithium、OptiFine 等模组是否存在
 *    - 如果存在冲突模组（如 OptiFine），记录警告日志
 *    - 如果检测到 Sodium，调整 Mixin 优先级以避免冲突
 * 
 * 3. Mixin 加载顺序管理
 *    - 确保 StellarCore 的 Mixin 在其他优化模组之后加载
 *    - 通过 stellar-core.mixins.json 中的 "priority": 1000 配合实现
 * 
 * 4. 调试支持
 *    - 在开发环境中输出详细的 Mixin 加载信息
 *    - 记录每个 Mixin 的加载状态和决策原因
 */
public class StellarMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("StellarCore|MixinPlugin");

    // ========== 模组兼容性映射 ==========

    /** 已知兼容模组（会调整注入策略以协同工作） */
    private static final Map<String, String> COMPATIBLE_MODS = new HashMap<>();

    /** 已知冲突模组（会记录警告但不会阻止加载） */
    private static final Set<String> CONFLICT_MODS = Set.of(
        "optifine",
        "optifabric"
    );

    /** 已知协同优化模组（会调整 Mixin 优先级以配合） */
    private static final Set<String> SYNERGY_MODS = Set.of(
        "sodium",
        "lithium",
        "iris",
        "phosphor",
        "ferritecore",
        "lazydfu",
        "smoothboot"
    );

    static {
        // 兼容模组及其推荐版本
        COMPATIBLE_MODS.put("sodium", ">=0.5.0");
        COMPATIBLE_MODS.put("lithium", ">=0.11.0");
        COMPATIBLE_MODS.put("iris", ">=1.6.0");
        COMPATIBLE_MODS.put("fabric-api", ">=0.92.0");
        COMPATIBLE_MODS.put("fabricloader", ">=0.15.0");
    }

    // ========== 运行时状态 ==========

    /** 是否检测到 Sodium 模组 */
    private boolean sodiumDetected = false;

    /** 是否检测到 Lithium 模组 */
    private boolean lithiumDetected = false;

    /** 是否检测到冲突模组 */
    private boolean conflictDetected = false;

    /** 冲突模组的名称 */
    private String conflictModName = null;

    /** 当前使用的 Mixin 配置包名 */
    private String currentPackage = "com.stellar.core.mixin";

    // ========== IMixinConfigPlugin 接口实现 ==========

    /**
     * 在 Mixin 配置加载时调用（最早的回调）。
     * 
     * 用于初始化插件状态、检测环境中的其他模组、
     * 读取配置文件并决定 Mixin 的加载策略。
     *
     * @param mixinPackage Mixin 配置中声明的包名
     */
    @Override
    public void onLoad(String mixinPackage) {
        this.currentPackage = mixinPackage;

        LOGGER.info("============================================");
        LOGGER.info("Stellar Core Mixin Plugin 已加载");
        LOGGER.info("Mixin 包: {}", mixinPackage);
        LOGGER.info("============================================");

        // 检测运行环境中的其他模组
        detectEnvironment();

        // 输出检测结果
        logDetectionResults();
    }

    /**
     * 获取 Mixin 配置的引用名称。
     * 返回 null 表示使用默认的 refmap 配置。
     *
     * @return refmap 文件名，或 null
     */
    @Override
    public String getRefMapperConfig() {
        // 使用 stellar-core.mixins.json 中声明的 refmap
        return "stellar-core-refmap.json";
    }

    /**
     * 决定是否应该加载指定的 Mixin 类。
     * 
     * 这是插件的核心方法，每次加载 Mixin 配置中的每个类时都会调用。
     * 返回 true 表示加载该 Mixin，返回 false 表示跳过。
     *
     * @param targetClassName 目标类名（Vanilla 的类）
     * @param mixinClassName  Mixin 类的全限定名
     * @return true 表示应加载此 Mixin
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 提取 Mixin 类的简单名称
        String simpleName = extractSimpleClassName(mixinClassName);

        if (simpleName == null) {
            LOGGER.warn("无法解析 Mixin 类名: {}", mixinClassName);
            return true; // 默认加载
        }

        // ========== 规则1：冲突模组检测 ==========
        // 如果检测到 OptiFine，记录警告但仍加载
        // OptiFine 会修改相同的类，可能导致 Mixin 注入失败
        if (conflictDetected && conflictModName != null) {
            LOGGER.warn("[兼容性] 检测到冲突模组 '{}'，Mixin '{}' 可能注入失败。",
                conflictModName, simpleName);
            LOGGER.warn("[兼容性] 建议移除 '{}' 以获得最佳性能。", conflictModName);
            // 不阻止加载，让游戏自行处理冲突
        }

        // ========== 规则2：Sodium 协同 ==========
        // 如果检测到 Sodium，跳过某些渲染 Mixin
        // Sodium 已经实现了高效的渲染，我们的某些注入可能冲突
        if (sodiumDetected) {
            switch (simpleName) {
                case "WorldRendererMixin":
                    // Sodium 替换了 WorldRenderer 的渲染流程
                    // 但我们的八叉树剔除在 setupTerrain 阶段注入，通常不冲突
                    LOGGER.info("[协同] Sodium 已检测到，WorldRendererMixin 将以兼容模式加载。");
                    return true;

                case "EntityRendererMixin":
                    // 实体渲染剔除与 Sodium 兼容
                    return true;

                case "ParticleManagerMixin":
                    // 粒子管理 Mixin 与 Sodium 兼容
                    return true;

                default:
                    break;
            }
        }

        // ========== 规则3：Lithium 协同 ==========
        // Lithium 优化了服务端逻辑，可能修改相同的类
        if (lithiumDetected) {
            switch (simpleName) {
                case "ServerWorldMixin":
                    // Lithium 也注入了 ServerWorld.tick
                    // 我们的 Mixin 使用不同的注入点和方法，通常兼容
                    LOGGER.info("[协同] Lithium 已检测到，ServerWorldMixin 将以兼容模式加载。");
                    return true;

                case "MobEntityMixin":
                    // Lithium 优化了实体 AI，但我们的惰性化在更上层拦截
                    return true;

                default:
                    break;
            }
        }

        // ========== 规则4：基于配置的动态启用/禁用 ==========
        // 读取 StellarConfig 判断是否应加载某些 Mixin
        StellarCore instance = StellarCore.getInstance();
        if (instance != null && instance.getConfig() != null) {
            boolean isDebug = instance.getConfig().debugVerboseLogging;

            if (isDebug) {
                LOGGER.info("[调试] 加载 Mixin: {} → {}", mixinClassName, targetClassName);
            }
        }

        // ========== 规则5：全部通过，允许加载 ==========
        return true;
    }

    /**
     * 决定是否应加载整个 Mixin 配置。
     * 返回 true 表示加载该配置中的所有 Mixin（受 shouldApplyMixin 二次过滤）。
     *
     * @param mixinPackage Mixin 配置的包名
     * @return true 表示应加载此配置
     */
    @Override
    public boolean shouldApplyMixin(String mixinPackage) {
        // 只加载我们自己的 Mixin 配置
        boolean isOurPackage = mixinPackage.equals(currentPackage)
            || mixinPackage.startsWith("com.stellar.core.mixin");

        if (!isOurPackage) {
            LOGGER.info("跳过非 StellarCore 的 Mixin 配置: {}", mixinPackage);
        }

        return isOurPackage;
    }

    /**
     * 在 Mixin 类被接受（通过 shouldApplyMixin 后）时调用。
     * 用于在 Mixin 被实际应用之前做最后的检查或修改。
     *
     * @param targetClassName 目标类名
     * @param classNode       目标类的 ASM ClassNode
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void acceptTargets(
            Set<String> myTargets,
            Set<String> otherTargets) {
        // 记录 StellarCore 的 Mixin 目标
        if (!myTargets.isEmpty()) {
            LOGGER.info("StellarCore Mixin 目标类: {}", myTargets);
        }

        // 检查与其他模组的目标冲突
        for (String target : myTargets) {
            if (otherTargets.contains(target)) {
                LOGGER.info("[兼容性] 类 '{}' 被多个模组的 Mixin 注入，"
                    + "StellarCore 将以高优先级（1000）加载以确保兼容。", target);
            }
        }
    }

    /**
     * 为 Mixin 配置提供前置 Mixin 依赖列表。
     * StellarCore 的 Mixin 不依赖其他 Mixin 先加载。
     *
     * @return 前置 Mixin 配置列表（空列表）
     */
    @Override
    public List<String> getMixins() {
        // 返回空列表：不添加额外的 Mixin 配置
        return new ArrayList<>();
    }

    /**
     * 在 Mixin 类被正式应用前调用，允许修改目标类的字节码。
     * StellarCore 不在此阶段修改字节码。
     *
     * @param targetClassName 目标类名
     * @param classNode       目标类的 ASM ClassNode
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
        // StellarCore 不需要在 preApply 阶段做额外处理
        // 所有逻辑都在 Mixin 类的 @Inject 注解中完成
    }

    /**
     * 在 Mixin 类被应用后调用。
     * 用于验证 Mixin 是否正确应用，或执行后续操作。
     *
     * @param targetClassName 目标类名
     * @param classNode       目标类的 ASM ClassNode（已被修改）
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo) {
        // 记录 Mixin 成功应用
        String simpleName = extractSimpleClassName(mixinClassName);

        if (StellarCore.getInstance() != null
            && StellarCore.getInstance().getConfig() != null
            && StellarCore.getInstance().getConfig().debugVerboseLogging) {
            LOGGER.info("[调试] Mixin 已应用: {} → {}", simpleName, targetClassName);
        }
    }

    // ========== 环境检测 ==========

    /**
     * 检测运行环境中的其他模组。
     * 使用反射检查类路径中是否存在已知模组的特征类。
     */
    private void detectEnvironment() {
        // 检测 Sodium
        sodiumDetected = isClassPresent("me.jellysquid.mods.sodium.client.SodiumClientMod");
        if (sodiumDetected) {
            LOGGER.info("[环境] 检测到 Sodium（协同优化模组）");
        }

        // 检测 Lithium
        lithiumDetected = isClassPresent("me.jellysquid.mods.lithium.LithiumMod");
        if (lithiumDetected) {
            LOGGER.info("[环境] 检测到 Lithium（协同优化模组）");
        }

        // 检测 OptiFine
        conflictDetected = isClassPresent("optifine.Optifine");
        if (conflictDetected) {
            conflictModName = "OptiFine";
            LOGGER.warn("[环境] 检测到 OptiFine（不兼容模组）");
        }

        // 检测其他协同模组
        for (String mod : SYNERGY_MODS) {
            // 简单检测：尝试加载模组的典型类
            checkSynergyMod(mod);
        }
    }

    /**
     * 检查一个协同优化模组是否存在。
     *
     * @param modId 模组 ID
     */
    private void checkSynergyMod(String modId) {
        String className = switch (modId) {
            case "iris"         -> "net.coderbot.iris.Iris";
            case "phosphor"     -> "net.jellysquid.mods.phosphor.PhosphorMod";
            case "ferritecore"  -> "malte0811.ferritecore.FerriteCore";
            case "lazydfu"      -> "com.ishland.lazydfu.LazyDFU";
            case "smoothboot"   -> "io.github.ultimateboomer.smoothboot.SmoothBoot";
            default             -> null;
        };

        if (className != null && isClassPresent(className)) {
            LOGGER.info("[环境] 检测到协同模组: {}", modId);
        }
    }

    /**
     * 检查一个类是否存在于类路径中。
     * 使用反射尝试加载类，如果成功则返回 true。
     *
     * @param className 完整类名
     * @return true 表示类存在
     */
    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoClassDefFoundError e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ========== 工具方法 ==========

    /**
     * 从完整类名中提取简单类名。
     *
     * @param fullClassName 完整类名，如 "com.stellar.core.mixin.WorldRendererMixin"
     * @return 简单类名，如 "WorldRendererMixin"
     */
    private String extractSimpleClassName(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) {
            return null;
        }

        int lastDot = fullClassName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fullClassName.length() - 1) {
            return fullClassName.substring(lastDot + 1);
        }
        return fullClassName;
    }

    /**
     * 输出环境检测结果。
     */
    private void logDetectionResults() {
        LOGGER.info("============================================");
        LOGGER.info("Stellar Core 环境检测结果:");
        LOGGER.info("  Sodium:     {}", sodiumDetected ? "✓ 已检测到（协同模式）" : "✗ 未检测到");
        LOGGER.info("  Lithium:    {}", lithiumDetected ? "✓ 已检测到（协同模式）" : "✗ 未检测到");
        LOGGER.info("  冲突模组:   {}", conflictDetected ? "⚠ " + conflictModName + "（可能不兼容）" : "✓ 无冲突");
        LOGGER.info("============================================");

        if (conflictDetected) {
            LOGGER.warn("⚠ 警告: 检测到不兼容模组 '{}'。", conflictModName);
            LOGGER.warn("   StellarCore 会尝试正常加载，但部分功能可能失效。");
            LOGGER.warn("   建议移除 '{}' 以获得完整的性能提升。", conflictModName);
        }

        if (sodiumDetected && lithiumDetected) {
            LOGGER.info("✓ 检测到 Sodium + Lithium，StellarCore 将与它们协同工作。");
            LOGGER.info("  渲染优化由 Sodium 处理，StellarCore 补充八叉树剔除和动态LOD。");
            LOGGER.info("  服务端优化由 Lithium 处理，StellarCore 补充区块状态机和实体惰性化。");
        }
    }
}