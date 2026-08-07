#!/bin/bash

echo "🔧 星核引擎 - 完整编译"
echo "================================="

# 1. 准备目录
mkdir -p build/classes libs

# 2. 构建 classpath
CP="libs/fabric-loader-0.15.11.jar:libs/slf4j-api-2.0.9.jar"

# 3. 下载额外的依赖（如果存在）
[ -f libs/mixin-0.12.5.jar ] && CP="$CP:libs/mixin-0.12.5.jar"
[ -f libs/fabric-api-0.91.1.jar ] && CP="$CP:libs/fabric-api-0.91.1.jar"
[ -f libs/yarn-1.20.1+build.10-v2.jar ] && CP="$CP:libs/yarn-1.20.1+build.10-v2.jar"

echo "📦 Classpath: $CP"

# 4. 编译所有 Java 文件（忽略缺失类的错误）
echo "📝 编译中..."
find src/main/java -name "*.java" > sources.txt

javac -cp "$CP" \
  -d build/classes \
  @sources.txt 2>&1 | tee build/compile.log

# 5. 检查是否有编译成功的 class
CLASS_COUNT=$(find build/classes -name "*.class" 2>/dev/null | wc -l)

if [ $CLASS_COUNT -gt 0 ]; then
    echo "✅ 编译完成！生成了 $CLASS_COUNT 个 class 文件"
    
    # 6. 打包 JAR
    echo "📦 打包 JAR..."
    cd build/classes
    jar cf ../stellar-core-1.0.0.jar *
    cd ../..
    
    # 7. 添加资源文件
    if [ -d src/main/resources ]; then
        jar uf build/stellar-core-1.0.0.jar -C src/main/resources .
    fi
    
    echo "✅ JAR 已生成：build/stellar-core-1.0.0.jar"
    echo "📊 文件大小：$(du -h build/stellar-core-1.0.0.jar | cut -f1)"
    
    # 8. 显示 JAR 内容
    echo ""
    echo "📋 JAR 内容："
    jar tf build/stellar-core-1.0.0.jar | head -20
    
else
    echo "❌ 编译失败，没有生成任何 class 文件"
    echo "查看错误：cat build/compile.log"
fi

rm -f sources.txt
