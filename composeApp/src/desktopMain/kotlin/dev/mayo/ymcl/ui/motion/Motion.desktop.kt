package dev.mayo.ymcl.ui.motion

internal actual fun platformReduceMotionOverride(): Boolean =
    System.getProperty("ymcl.reduceMotion")?.equals("true", ignoreCase = true) ?: false
