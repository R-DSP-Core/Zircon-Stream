#!/bin/bash
# 文件名：create_bundles.sh
# 用途：为三个已知仓库生成 bundle 文件，并压缩成 bundles.zip

set -e  # 出错就退出

ZIP_FILE="bundles.zip"

echo "开始生成 bundle 文件..."

# 先删除旧的压缩文件（如果存在）
[ -f "$ZIP_FILE" ] && rm "$ZIP_FILE"

# 根目录仓库
git bundle create "Zircon-2024-main.bundle" --all
echo "生成 bundle: Zircon-2024-main.bundle"

# RV-Software 仓库
git -C RV-Software bundle create "../RV-Software.bundle" --all
echo "生成 bundle: RV-Software.bundle"

# ZirconSim 仓库
git -C ZirconSim bundle create "../ZirconSim.bundle" --all
echo "生成 bundle: ZirconSim.bundle"

# 压缩三个 bundle 文件
zip "$ZIP_FILE" Zircon-2024-main.bundle RV-Software.bundle ZirconSim.bundle
echo "已压缩成 $ZIP_FILE"

# 可选：删除单独的 bundle 文件，只保留压缩文件
rm Zircon-2024-main.bundle RV-Software.bundle ZirconSim.bundle

echo "所有操作完成！"
