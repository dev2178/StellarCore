#!/bin/bash

# ============================================================
#  Stellar Core Engine - 手动编译脚本
#  适用于 Termux / 无 Gradle 网络环境
#  用法: bash compile.sh [clean|build|package|all]
# ============================================================

set -e  # 任何命令失败立即退出

# ---------- 项目配置 ----------
PROJECT_NAME="stellar-core"
VERSION="1.0.0"
MAIN_CLASS="com.stellar.core.StellarCore"

# 目录路径
SRC_DIR="src/main/java"
RES_DIR="src/main/resources"
LIBS_DIR="libs"
BUILD_DIR="build"
CLASSES_DIR="$BUILD_DIR/classes"
OUTPUT_JAR="$BUILD_DIR/${PROJECT_NAME}-${VERSION}.jar"

# 依赖列表（按顺序排列 classpath）
DEPS=(
    "$LIBS_DIR/fabric-loader-0.15.11.jar"
    "$LIBS_DIR/slf4j-api-2.0.9.jar"
    "$LIBS_DIR/minecraft-1.20.1-intermediary.jar"
    "$LIBS_DIR/fabric-api-0.92.0.jar"
)

# ---------- 颜色输出 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
print_ok()    { echo -e "${GREEN}[ OK ]${NC} $1"; }
print_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ---------- 函数：检查依赖 ----------
check_dependencies() {
    print_info "检查依赖库..."
    local missing=0
    for dep in "${DEPS[@]}"; do
        if [ ! -f "$dep" ]; then
            print_error "缺少依赖: $dep"
            missing=1
        fi
    done

    if [ $missing -eq 1 ]; then
        echo ""
        print_warn "请手动下载以下依赖到 $LIBS_DIR/ 目录："
        echo ""
        echo "  # 创建 libs 目录"
        echo "  mkdir -p $LIBS_DIR"
        echo ""
        echo "  # 下载 Fabric Loader"
        echo "  curl -L -o $LIBS_DIR/fabric-loader-0.15.11.jar \\"
        echo "    https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar"
        echo ""
        echo "  # 下载 SLF4J 日志库"
        echo "  curl -L -o $LIBS_DIR/slf4j-api-2.0.9.jar \\"
        echo "    https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
        echo ""
        echo "  # 下载 Minecraft 1.20.1 映射库（Yarn映射）"
        echo "  curl -L -o $LIBS_DIR/minecraft-1.20.1-mapped.jar \\"
        echo "    https://maven.fabricmc.net/net/fabricmc/yarn/1.20.1+build.10/yarn-1.20.1+build.10-v2.jar"
        echo ""
        echo "  # 下载 Fabric API（较大，约2MB）"
        echo "  curl -L -o $LIBS_DIR/fabric-api-0.92.0.jar \\"
        echo "    https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.92.0/fabric-api-0.92.0.jar"
        echo ""
        print_error "依赖缺失，请下载后重新运行。退出。"
        exit 1
    fi
    print_ok "所有依赖已就绪。"
}

# ---------- 函数：清理 ----------
do_clean() {
    print_info "清理旧的构建文件..."
    if [ -d "$BUILD_DIR" ]; then
        rm -rf "$BUILD_DIR"
        print_ok "已删除 $BUILD_DIR/"
    else
        print_info "无旧构建文件，跳过。"
    fi
}

# ---------- 函数：创建目录 ----------
prepare_dirs() {
    print_info "创建输出目录..."
    mkdir -p "$CLASSES_DIR"
    print_ok "目录已就绪: $CLASSES_DIR"
}

# ---------- 函数：构建 classpath ----------
build_classpath() {
    local cp=""
    for dep in "${DEPS[@]}"; do
        if [ -z "$cp" ]; then
            cp="$dep"
        else
            cp="$cp:$dep"
        fi
    done
    echo "$cp"
}

# ---------- 函数：编译 ----------
do_compile() {
    print_info "开始编译 Java 源代码..."

    # 收集所有 .java 文件
    local java_files=$(find "$SRC_DIR" -name "*.java" -type f 2>/dev/null)
    if [ -z "$java_files" ]; then
        print_error "在 $SRC_DIR/ 下未找到任何 .java 文件！"
        print_error "请确认源代码已放置到正确位置。"
        exit 1
    fi

    local java_count=$(echo "$java_files" | wc -l)
    print_info "找到 $java_count 个 Java 源文件："
    echo "$java_files" | while read f; do
        echo "    $f"
    done

    # 构建 classpath
    local classpath=$(build_classpath)
    print_info "Classpath: $classpath"

    # 执行编译
    print_info "编译中..."
    if javac -cp "$classpath" \
        -d "$CLASSES_DIR" \
        -source 17 \
        -target 17 \
        -encoding UTF-8 \
        -Xlint:all \
        $java_files 2>&1; then
        print_ok "编译成功！"
    else
        print_error "编译失败，请检查上方错误信息。"
        print_info "常见错误排查："
        echo "  1. 检查 import 语句是否正确"
        echo "  2. 检查包名是否与目录结构一致"
        echo "  3. 检查是否缺少依赖 jar"
        echo "  4. 检查 Java 版本是否为 17 (当前: $(java -version 2>&1 | head -1))"
        exit 1
    fi
}

# ---------- 函数：打包 ----------
do_package() {
    print_info "打包 JAR 文件..."

    # 检查 class 文件是否存在
    local class_count=$(find "$CLASSES_DIR" -name "*.class" -type f 2>/dev/null | wc -l)
    if [ "$class_count" -eq 0 ]; then
        print_error "$CLASSES_DIR/ 下无 .class 文件，请先执行编译。"
        exit 1
    fi
    print_info "找到 $class_count 个 .class 文件"

    # 创建初始 JAR（仅含 class 文件）
    cd "$CLASSES_DIR"
    jar cf "../../${PROJECT_NAME}-${VERSION}.jar" *
    cd - > /dev/null
    print_ok "初始 JAR 已创建（仅 class 文件）"

    # 合并资源文件
    if [ -d "$RES_DIR" ]; then
        print_info "合并资源文件..."
        local res_count=$(find "$RES_DIR" -type f | wc -l)
        print_info "找到 $res_count 个资源文件"

        # 使用 jar uf 添加资源
        # 需要进入资源目录以保持正确的路径结构
        cd "$RES_DIR"
        jar uf "../../$OUTPUT_JAR" *
        cd - > /dev/null
        print_ok "资源文件已合并。"
    else
        print_warn "资源目录 $RES_DIR/ 不存在，跳过资源合并。"
    fi
}

# ---------- 函数：验证 ----------
do_verify() {
    print_info "验证 JAR 文件..."

    if [ ! -f "$OUTPUT_JAR" ]; then
        print_error "JAR 文件不存在: $OUTPUT_JAR"
        exit 1
    fi

    local jar_size=$(ls -lh "$OUTPUT_JAR" | awk '{print $5}')
    print_info "JAR 大小: $jar_size"

    # 检查必须包含的文件
    print_info "检查必须文件..."
    local checks=(
        "fabric.mod.json"
        "stellar-core.mixins.json"
        "com/stellar/core/StellarCore.class"
        "com/stellar/core/config/StellarConfig.class"
        "com/stellar/core/render/ChunkRenderCache.class"
        "com/stellar/core/render/OctreeFrustumCuller.class"
        "com/stellar/core/render/DynamicLOD.class"
        "com/stellar/core/logic/ChunkStateManager.class"
        "com/stellar/core/logic/LazyEntityAI.class"
        "com/stellar/core/logic/LazyRedstone.class"
        "com/stellar/core/mixin/WorldRendererMixin.class"
        "com/stellar/core/mixin/EntityRendererMixin.class"
        "com/stellar/core/mixin/ParticleManagerMixin.class"
        "com/stellar/core/mixin/ServerWorldMixin.class"
        "com/stellar/core/mixin/MobEntityMixin.class"
        "com/stellar/core/mixin/RedstoneWireMixin.class"
    )

    local all_ok=true
    for check in "${checks[@]}"; do
        if jar tf "$OUTPUT_JAR" | grep -q "$check"; then
            print_ok "  ✓ $check"
        else
            print_error "  ✗ $check (缺失！)"
            all_ok=false
        fi
    done

    if [ "$all_ok" = true ]; then
        print_ok "全部必须文件验证通过！"
    else
        print_error "部分文件缺失，请检查编译和打包过程。"
        exit 1
    fi

    # 显示完整内容列表
    echo ""
    print_info "JAR 完整内容列表："
    jar tf "$OUTPUT_JAR" | head -50
    local total_entries=$(jar tf "$OUTPUT_JAR" | wc -l)
    if [ "$total_entries" -gt 50 ]; then
        echo "    ... 及其他 $(($total_entries - 50)) 个条目"
    fi
}

# ---------- 函数：复制到下载目录 ----------
do_copy() {
    print_info "复制 JAR 到 Download 目录..."
    local download_dir="$HOME/storage/downloads"
    if [ -d "$download_dir" ]; then
        cp "$OUTPUT_JAR" "$download_dir/"
        print_ok "已复制到: $download_dir/${PROJECT_NAME}-${VERSION}.jar"
        print_info "你可以用文件管理器将此文件移动到 Minecraft 模组文件夹。"
    else
        print_warn "Download 目录不存在，跳过复制。"
        print_info "JAR 文件位于: $OUTPUT_JAR"
    fi
}

# ---------- 主流程 ----------
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║     Stellar Core Engine - 手动编译脚本              ║"
echo "║     Version: $VERSION                                ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# 解析命令
CMD="${1:-all}"

case "$CMD" in
    clean)
        do_clean
        print_ok "清理完成。"
        ;;
    check)
        check_dependencies
        print_ok "依赖检查完成。"
        ;;
    build)
        check_dependencies
        prepare_dirs
        do_compile
        print_ok "编译完成。"
        ;;
    package)
        do_package
        do_verify
        print_ok "打包完成。"
        ;;
    verify)
        do_verify
        print_ok "验证完成。"
        ;;
    copy)
        do_copy
        print_ok "复制完成。"
        ;;
    all|*)
        print_info "执行完整编译流程..."
        check_dependencies
        do_clean
        prepare_dirs
        do_compile
        do_package
        do_verify
        do_copy
        echo ""
        echo "╔══════════════════════════════════════════════════════╗"
        echo "║  ✅ 编译打包完成！                                   ║"
        echo "║                                                     ║"
        echo "║  输出文件: $OUTPUT_JAR"
        echo "║  文件大小: $(ls -lh "$OUTPUT_JAR" | awk '{print $5}')"
        echo "║                                                     ║"
        echo "║  安装步骤:                                          ║"
        echo "║  1. 将 JAR 复制到 Minecraft mods 文件夹             ║"
        echo "║  2. 确保已安装 Fabric Loader 0.15.0+                ║"
        echo "║  3. 启动游戏（选择 Fabric 版本）                    ║"
        echo "║  4. 在游戏内输入 /stellarcore stats 查看优化统计    ║"
        echo "║                                                     ║"
        echo "╚══════════════════════════════════════════════════════╝"
        ;;
esac

echo ""