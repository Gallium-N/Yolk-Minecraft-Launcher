package dev.mayo.ymcl.domain

enum class InstanceStatus(val label: String) {
    RUNNING("运行中"),
    READY("就绪"),
    READ_FAILED("读取失败"),
    UPDATE_AVAILABLE("可更新"),
}
