#!/bin/bash
# 文件名：create_bundles.sh
# 用途：直接为三个已知仓库生成 bundle 文件

echo "开始生成 bundle 文件..."

# 根目录仓库
git bundle create Zircon-2024-main.bundle --all
echo "生成根目录 bundle: Zircon-2024-main.bundle"

# RV-Software 仓库
git -C RV-Software bundle create RV-Software.bundle --all
echo "生成 RV-Software.bundle"

# ZirconSim 仓库
git -C ZirconSim bundle create ZirconSim.bundle --all
echo "生成 ZirconSim.bundle"

echo "所有 bundle 生成完毕！"
chm