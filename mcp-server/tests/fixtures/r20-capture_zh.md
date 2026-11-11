# 脱敏历史回归样本

[English](r20-capture.md) | [简体中文](r20-capture_zh.md)

`round-20-810-timing-compact-v1.json.gz` 是历史原生客户端记录的脱敏派生样本，仅供离线回归，不是当前世界证据或重放指令。

解压内容 SHA256：`e3dfe4f792f266ab129ec98e505bb8f8b014bcecd572e214f4c074bed177a0fd`。

玩家名、UUID/会话关联、世界名和本机文件系统根路径替换为合成身份/占位符，同一身份在整个样本中保持一致映射。几何、库存关系、NBT 结构、操作顺序和数值证据继续用于回归；不再声称与原始采集逐字节相同，不包含凭据字段。

`test_owned_replacement_consumer.py` 解码引用池。`MCB_TEST_R20_CAPTURE` 可指定同一脱敏 JSON/gzip 样本的绝对路径，解压哈希必须匹配；测试不需要客户端安装或外部构建目录。
