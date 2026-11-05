package dev.mayo.ymcl.domain

enum class InstanceStatus(val label: String) {
    RUNNING("运行中"),
    READY("就绪"),
    UPDATE_AVAILABLE("可更新"),
}
