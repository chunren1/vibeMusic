#!/bin/bash
# 首次启动自动安装 IK 分词器（已安装则跳过）
if [ ! -d /usr/share/elasticsearch/plugins/analysis-ik ]; then
  echo ">> 安装 IK 分词器..."
  elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-ik/8.18.0
fi
# 启动 ES
exec /usr/share/elasticsearch/bin/elasticsearch
